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

/**
 * The QR estate, asserted as a shape rather than described in a comment.
 *
 * ── WHY A STRUCTURAL TEST AND NOT A LIST IN A README ─────────────────────────────────────────
 *
 * The requirement this file guards is "every place a code can be read offers BOTH the camera and an
 * uploaded picture". That is a claim about a SET of surfaces, and a set is exactly the kind of claim
 * that decays: somebody adds a fourth screen that reads a tag, wires it to `BarcodeDetector` because
 * that is what the nearest example did, and the new screen silently has one way in on a laptop with
 * no camera and none at all on a laptop without the API. Nothing fails. The feature is simply
 * missing in one place, and the only way anybody finds out is a designer in a district office who
 * cannot get past it.
 *
 * So rather than assert "these three surfaces are fine", this asserts the invariant that MAKES them
 * fine and keeps making it true for surfaces that do not exist yet:
 *
 *   1. Exactly ONE component in the whole client turns a scanned or typed string into a record
 *      reference, and exactly ONE module decodes a picture. A surface cannot get this wrong without
 *      first reimplementing one of them, which is what this test refuses.
 *   2. That one component offers BOTH inputs, unconditionally, plus the keyboard.
 *   3. Every surface that reads a code mounts that component and nothing else.
 *
 * ── AND THE OTHER HALF OF THE ESTATE: WHERE A CODE IS SHOWN ──────────────────────────────────
 *
 * Reading was pinned here and SHOWING was not, which left the more likely omission unguarded. A
 * missing scanner is discovered the first time somebody holds up a card; a missing card is
 * discovered by nobody, because a record screen with no QR on it looks exactly like a record screen.
 * That is how the web came to show no code for a questionnaire interview or a media file while
 * Android showed both — two clients disagreeing about which records even HAVE a code, with nothing
 * failing anywhere. So the mount list for `RecordCodeCard` is pinned the same way the scanner's is,
 * by exact equality, and the record TYPES those mounts cover are checked against the grammar itself
 * so that adding a ninth kind of record cannot quietly ship without its card.
 *
 * ── WHY IT READS SOURCE FILES ────────────────────────────────────────────────────────────────
 *
 * There is no runtime handle on "how many QR surfaces does this app have"; the answer lives in the
 * import graph. Reading the graph is the only way to ask the question, and it is cheap, exact and
 * runs with no browser and no server — so it will still be run by somebody who cannot start the
 * stack, which is precisely who is most likely to add the fourth screen.
 *
 * PURE NODE. No page, no sign-in, no API.
 */

const ROOT = resolve(__dirname, "..");

/** The shared control every code-reading surface must go through. */
const SHARED_CONTROL = "components/designworkshop/WorkshopCodeScanner.tsx";

/** The one module that turns a File or a video frame into a decoded string. */
const IMAGE_DECODER = "lib/qrImageDecode.ts";

/** The pure decoder the module above feeds. Separate so it can be tested without a browser. */
const PURE_DECODER = "lib/qrDecode.ts";

/** The payload grammar. Declares `decodeWorkshopCode`, so it is expected to name it. */
const PAYLOAD_GRAMMAR = "lib/workshopCodes.ts";

/** The one component that draws a record's own code on the record's own screen. */
const RECORD_CARD = "components/RecordCode.tsx";

/** The sheet that lays codes out on paper — for thirty cards, and for one. */
const PRINT_SHEET = "components/designworkshop/WorkshopCodeSheet.tsx";

/**
 * The one record type that has no `RecordCodeCard`, and why it is not an omission.
 *
 * A prototype is a row inside one design workshop's draft, not a record in the repository: it has no
 * screen of its own, it is often only on the device that made it, and its tag is printed from that
 * workshop's Cards & tags sheet. `lookUpWorkshopCode` refuses a prototype reference for the same
 * reason. If a prototype ever gets a record screen, delete this and let the assertion below fail
 * until it has a card.
 */
const NO_CARD: readonly string[] = ["prototype"];

/**
 * Every source file of the client, as repo-relative POSIX paths.
 *
 * `e2e/` is deliberately excluded: a spec naming `BarcodeDetector` is a spec ABOUT the feature, and
 * counting it as a surface would make this test fail on itself.
 */
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

/**
 * A file's source with its comments removed and its string literals left intact.
 *
 * ── WHY EVERY ASSERTION RUNS AGAINST THIS AND NEVER AGAINST THE RAW TEXT ─────────────────────
 *
 * This repository's house style is long prose comments that quote the defect they prevent, so
 * `WorkshopCodeScanner.tsx` legitimately contains the sentence "the two buttons were rendered behind
 * `support === "yes"`". An assertion that the file must NOT contain `support === "yes"` therefore
 * matched the explanation of why it no longer does. A test that cannot tell code from prose is
 * unusable in a codebase written like this one.
 *
 * ── WHY IT IS A SCANNER AND NOT TWO REGULAR EXPRESSIONS ──────────────────────────────────────
 *
 * It was two — `replace(/\/\*[\s\S]*?\*\//g, "")` and a line-comment strip — and that version was
 * actively dangerous rather than merely imprecise. `accept="image/*"` contains the two characters
 * that open a block comment, so the strip began INSIDE that attribute and ran to the next `*​/`
 * anywhere below it, silently deleting the file input, its change handler and the video element
 * before any assertion looked at them. The test then reported that the control had no
 * `accept="image/*"` — which was true of the mangled copy and false of the file — and three other
 * assertions passed only because what they looked for happened to sit above the deletion.
 *
 * A test that quietly removes the code it is about to inspect is worse than no test at all: it
 * fails for reasons that have nothing to do with the product, and it PASSES for reasons that have
 * nothing to do with the product. So this walks the source, keeps string and template literals
 * whole, and only treats `/*` and `//` as comments when they are not inside one.
 *
 * Quote tracking resets at a newline for `'` and `"`, which is not a shortcut: neither can span a
 * line in TypeScript, and the reset is what stops an apostrophe in JSX prose ("the artisan's card")
 * from swallowing the rest of a file. Backticks may span lines and are tracked across them.
 */
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
      // A space, not nothing: `a/*x*/b` is two tokens and must not become one identifier.
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

/** Files whose CODE matches — a file that only discusses an API is not using it. */
function filesUsing(pattern: RegExp): string[] {
  return files.filter((path) => pattern.test(codeOf(path)));
}

/**
 * Files that CALL a function, excluding the one that declares it.
 *
 * The distinction matters here more than it usually would: the assertions below are of the form
 * "exactly one file calls this", and a declaration site counted as a call makes every one of them
 * off by one — which reads as a violation and is not one.
 */
function filesCalling(name: string): string[] {
  return filesUsing(new RegExp(`(?<!function\\s)\\b${name}\\s*\\(`));
}

test("exactly one component turns a scanned or typed code into a record reference", () => {
  // `decodeWorkshopCode` is the gate between "some characters arrived" and "this names a record".
  // A second caller is a second idea of what a valid code is — and the one that matters is the check
  // digit, which is all that stands between one mistyped character and two days of work attached to
  // somebody else's prototype.
  expect(filesCalling("decodeWorkshopCode")).toEqual([SHARED_CONTROL]);
  // The grammar itself declares it; it is not a caller, and this pins that distinction so the
  // assertion above cannot be satisfied by the function quietly moving.
  expect(sourceOf.get(PAYLOAD_GRAMMAR)).toContain("export function decodeWorkshopCode");
});

test("the decoding layers stay stacked: one surface, one image decoder, one pure decoder", () => {
  // THE DEFECT THIS EXISTS TO PREVENT, in its original form: `WorkshopCodeScanner` used to call
  // `BarcodeDetector` directly, for both the camera and the upload, and hid BOTH controls when it
  // was absent. It is absent on every Windows laptop this client runs on (measured — see
  // `lib/identityCardLocal.ts`), so the two ways in were not degraded on those machines, they were
  // gone. Any file reaching for that API again outside the one module that treats it as an optional
  // fast path is that defect coming back.
  expect(filesUsing(/\bBarcodeDetector\b/)).toEqual([IMAGE_DECODER]);

  // The picture entry points belong to the control; the pure entry point belongs to the image
  // decoder. Stated as two separate assertions because they are two different mistakes: a surface
  // calling `decodeQrFromGrey` would be a surface that had to build its own pixels, and a lib
  // calling `decodeQrFromFile` would be one that had quietly acquired a DOM dependency.
  expect(filesCalling("decodeQrFromFile")).toEqual([SHARED_CONTROL]);
  expect(filesCalling("decodeQrFromVideoFrame")).toEqual([SHARED_CONTROL]);
  expect(filesCalling("decodeQrFromGrey")).toEqual([IMAGE_DECODER]);
  expect(sourceOf.get(PURE_DECODER)).toContain("export function decodeQrFromGrey");
});

test("the shared control offers the camera AND an uploaded picture AND the keyboard, none of them gated", () => {
  const source = codeOf(SHARED_CONTROL);

  // The camera.
  expect(source).toContain("getUserMedia");
  expect(source).toContain('data-testid="workshop-code-scan"');
  // The upload: a real file input that accepts pictures, and the drop and paste routes that reach
  // the same function — a code arriving over WhatsApp is screenshotted, and making somebody save it
  // to disk first is a step the software invented.
  expect(source).toContain('data-testid="workshop-code-upload"');
  expect(source).toContain('type="file"');
  expect(source).toContain('accept="image/*"');
  expect(source).toContain("onDrop");
  expect(source).toContain('"paste"');
  // The keyboard, which is the only route that needs no camera, no file and no picture at all.
  expect(source).toContain('id="workshop-code-manual"');

  // NEITHER PICTURE ROUTE MAY BE RENDERED CONDITIONALLY ON A CAPABILITY PROBE. The old build read
  // `const cameraOffered = support === "yes"` and wrapped both buttons in it; this is the assertion
  // that would have caught that, and it is written against the shape of the defect rather than
  // against the identifier it happened to use.
  expect(source).not.toMatch(/cameraOffered|support\s*===\s*"(yes|no)"/);

  // All four routes must converge, or they are four different ideas of what a valid code is.
  expect(source).toContain("handleRawValue");
});

test("every surface that reads a code mounts the shared control, and there are no others", () => {
  // The inventory, derived rather than declared: any file that renders the control is a reading
  // surface, and the assertion below names the ones that exist today so that adding or losing one
  // is a visible change to this file rather than a silent change to the product.
  //
  //   - `/search` — the repository-wide panel, for a tool, product or artisan card. A scan is a
  //     search whose query happens to be exact.
  //   - `/design-workshops/[id]/codes` — Cards & tags, scoped to one workshop, which answers a
  //     prototype tag out of the local draft with no network at all.
  //   - `StageReferenceField` — the stage's REF picker. The other two REPORT what a code names; this
  //     one is the only surface where a scan ANSWERS A BOX, which is the half of "scanned by oneself
  //     and others" nothing in the app did. It resolves through the references endpoint's `recordId`
  //     parameter, so the scanned record is narrowed by the same scope and cascade the list is, and
  //     commits through the picker's own `choose`.
  //
  // `RecordCodeScanPanel` is counted as a surface in its own right because it is what `/search`
  // mounts; the page below is asserted to mount the panel, so the chain is complete.
  const mounts = filesUsing(/<WorkshopCodeScanner\b/);
  expect(mounts.sort()).toEqual(
    [
      "app/(protected)/design-workshops/[id]/codes/page.tsx",
      "components/RecordCodeScanPanel.tsx",
      "components/designworkshop/StageReferenceField.tsx"
    ].sort()
  );

  expect(sourceOf.get("app/(protected)/search/page.tsx")).toContain("<RecordCodeScanPanel");

  // And nothing else builds a scanner of its own out of a camera preview. `getUserMedia` is used by
  // dictation too, which is why this looks for the pairing that makes something a code reader — a
  // live camera AND a route into the payload grammar — rather than for the camera alone.
  for (const path of filesUsing(/getUserMedia/)) {
    if (path === SHARED_CONTROL) continue;
    expect(
      /decodeWorkshopCode|qrImageDecode|BarcodeDetector/.test(codeOf(path)),
      `${path} opens a camera and reads codes without going through ${SHARED_CONTROL}`
    ).toBe(false);
  }
});

/**
 * Every file that MOUNTS the record card, with the record types each of its mounts names.
 *
 * The type is read out of the mount itself rather than from a hand-kept table beside it, so a card
 * added for a new record type is counted the moment it renders. `[^>]*?` cannot leave the opening
 * tag, so a mount that passed `recordType` as a variable rather than a literal would yield a file
 * with NO types — which the test below fails on rather than quietly under-counting.
 */
function recordCardMounts(): Map<string, string[]> {
  const found = new Map<string, string[]>();
  for (const path of filesUsing(/<RecordCodeCard\b/)) {
    /*
      `[a-zA-Z]+` AND NOT `[a-z]+`, WHICH IS WHAT THIS READ UNTIL A CAMEL-CASE TYPE EXISTED.

      Every record type was a single lower-case word — artisan, craft, tool — so the narrower class
      was invisible for as long as that held. `designWorkshop` is the first that is not, and the
      failure it produced was the worst shape available: the FILE list below matched (the mount is
      really there and really parses), while the TYPE list silently came up one short, so the
      assertion read as "that type has no card anywhere" when the card was three lines away. A
      measuring pass that cannot see the thing it measures reports an absence, not an error.
    */
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

  // THE INVENTORY, named so that adding or losing a card is a visible change to this file rather
  // than a silent change to the product — the same contract the scanner mount list above carries.
  //
  //   - the three `/[id]/edit` routes — artisan, product, tool — each a record's own page.
  //   - `crafts`, `processes`, `workshops` — edited inline on their list page, so the card appears
  //     beside the row being edited.
  //   - `ArtisanForm` — the panel shown straight after a create, so a tag can be printed for the
  //     artisan still sitting in front of the researcher.
  //   - `design-workshops` — the DESIGN workshop (a `DesignWorkshop` row, letter `G`), not the
  //     `Workshop` on the line below it. Shown from its list row rather than from
  //     `/design-workshops/[id]`, because this code's job is to be held up in a room for other
  //     designers to scan onto the workshop, and the list is where the person holding the phone
  //     finds it. A row whose workshop exists only on that device renders the encoder's `dwlocal-`
  //     refusal instead of a symbol, which is the intended answer and not a missing card.
  //   - `questionnaire` and `media` — neither record type has a per-record web route at all.
  //     `workshopCodeLookup`'s OPEN_HREF says so in as many words, and lands an interview scan on
  //     `/questionnaire` and a media scan on the stored object or, when the caller is not entitled
  //     to the bytes, on `/media`. So the card is mounted on the expanded row of each of those two
  //     lists, which is the closest thing anybody opens for ONE of them. Android has shown a code
  //     for both all along; this is where the web stopped disagreeing with it.
  expect(files.sort()).toEqual(
    [
      "app/(protected)/artisans/[id]/edit/page.tsx",
      "app/(protected)/crafts/page.tsx",
      "app/(protected)/design-workshops/page.tsx",
      "app/(protected)/media/page.tsx",
      "app/(protected)/processes/page.tsx",
      "app/(protected)/products/[id]/edit/page.tsx",
      "app/(protected)/questionnaire/page.tsx",
      "app/(protected)/tools/[id]/edit/page.tsx",
      "app/(protected)/workshops/page.tsx",
      "components/forms/ArtisanForm.tsx"
    ].sort()
  );

  // THE ASSERTION THAT SURVIVES A NEW RECORD TYPE. The list above pins today's files; this pins the
  // requirement — every kind of record the grammar can encode is shown somewhere, prototype
  // excepted. A tenth type added to `workshopCodes.ts` fails here until it has a card, which is the
  // point: the type letter, the scanner and the lookup would all already work for it, and the only
  // thing missing would be the one surface a designer actually looks at.
  expect(types).toEqual(WORKSHOP_RECORD_TYPES.filter((type) => !NO_CARD.includes(type)).slice().sort());

  // Every mount must have named its type as a literal, or the list above is a list of files and the
  // coverage assertion beneath it is quietly checking fewer types than there are cards.
  expect([...mounts].filter(([, named]) => named.length === 0).map(([path]) => path)).toEqual([]);

  // And the card is declared in exactly one place, so there is one idea of what a shown code is.
  expect(sourceOf.get(RECORD_CARD)).toContain("export function RecordCodeCard");
});

test("the screen and the paper draw one record as ONE symbol", () => {
  // A record's code is shown by `RecordCode.tsx` and printed by `WorkshopCodeSheet.tsx` — and since
  // the card's Print control renders THROUGH the sheet, the same record passes through both files on
  // its way to paper. The payload is already shared (both call `encodeWorkshopCode` and neither has
  // any other source of one); the error-correction level is the one parameter each declares for
  // itself, and two levels produce two different-looking symbols for one record. That is not a
  // cosmetic difference: it is what makes somebody hold a printed tag next to a screen and distrust
  // the scan. Q on both, pinned here because nothing else would notice one of them drifting to L.
  for (const path of [RECORD_CARD, PRINT_SHEET]) {
    expect(codeOf(path), `${path} no longer declares its QR error-correction level as Q`).toContain(
      'const ECC_LEVEL = "Q" as const;'
    );
  }

  // One card printed from a record screen goes through the sheet and not through a second layout of
  // its own — the millimetres that decide whether a tag scans at all live there, and a copy of them
  // is a copy that drifts.
  expect(filesUsing(/<WorkshopCodeSheet\b/).sort()).toEqual(
    ["app/(protected)/design-workshops/[id]/codes/page.tsx", PRINT_SHEET].sort()
  );
  expect(codeOf(RECORD_CARD)).toContain("<WorkshopCodePrintout");
});

test("the pure decoder stays free of the DOM, so it can be tested and ported without a browser", () => {
  // The split that makes `e2e/qr-decode-unit.spec.ts` possible at all: seventeen tests over skew,
  // glare, damage and refusals, none of which needs a browser, a camera or a server. A `document` or
  // a `createImageBitmap` creeping into `qrDecode.ts` would take that away quietly — the tests would
  // keep passing until the first one that touched the new code path.
  //
  // The globals are matched with a leading non-member guard rather than as bare substrings, because
  // `DEBUG_DETECTION.window` is a legitimate PROPERTY of this module's debug hook and matching it as
  // a reference to the browser's `window` failed this test on a file that touches no DOM at all.
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

/* ────────────────────────────────────────────────────────────────────────────
 * Scanning into a stage's REF picker — the three refusals, EXECUTED
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * WHY THESE ARE CALLS AND NOT SOURCE ASSERTIONS, in a file that is otherwise all source assertions.
 *
 * Everything above asks "does this surface exist and does it go through the one control", which is a
 * question about the import graph and can only be answered by reading it. What a scan DOES when the
 * code is the wrong kind, or names a record another workshop owns, is a rule — and a rule read as a
 * substring is a rule whose SPELLING is pinned while the condition that reaches it is not. The
 * refusals are the whole safety of this lane: the wrong-type one is what stops an artisan's id being
 * looked up in the product table, and the out-of-scope one is what stops a cross-cluster record
 * being offered as an ordinary choice. Both were lifted out of the component for exactly this
 * reason, the same way `scopeNoticeLines` and `inlineSeed` were — there is no React renderer in this
 * repository's devDependencies, so a rule left inside a component body cannot be executed at all.
 *
 * None of this proves anything PAINTS. It proves which sentence the picker has decided on and, for
 * the case that matters most, that it decided on a sentence rather than on a row.
 */

function refField(overrides: Partial<DwField> & { key: string; label: string }): DwField {
  return { type: "REF", tier: "BASIC", required: false, ...overrides } as DwField;
}

/** Stage 6's product box: WORKSHOP-scoped, and cascaded off the artisan chosen on the same row. */
const PRODUCT_BOX = refField({
  key: "productRef",
  label: "Product",
  refModel: "ProductDocumentation",
  refScope: "WORKSHOP",
  refFilterBy: "artisanRef"
});

/** Stage 3's roster box: an artisan, WORKSHOP-scoped, no cascade. */
const ARTISAN_BOX = refField({ key: "artisanRef", label: "Artisan", refModel: "Artisan", refScope: "WORKSHOP" });

/** A box whose records are rows of this workshop and carry no printed code at all. */
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
  // BOTH halves, or the designer is left working out which of the card and the box is the mistake.
  expect(refusal).toContain("artisan");
  expect(refusal).toContain("product");
  expect(refusal).toContain("Product");

  // And the right kind is not refused, which is the assertion that would catch the table being
  // inverted — a refusal that fires on everything is as useless as one that fires on nothing.
  expect(scanTypeRefusal(PRODUCT_BOX, PRODUCT_CODE)).toBeNull();
  expect(scanTypeRefusal(ARTISAN_BOX, ARTISAN_CODE)).toBeNull();
  expect(scanTypeRefusal(ARTISAN_BOX, PRODUCT_CODE)).not.toBeNull();
});

test("a picker whose records carry no code is offered no reader, and refuses one if it is given", () => {
  // `DwSketch`, `DwCostSheet`, `DwFinalProduct` and `DwParticipant` have no entry in
  // `WORKSHOP_RECORD_TYPES`, so no card in existence can answer one of their boxes. A reader there
  // would be a control that refuses every code on earth, which reads as a broken scanner rather than
  // as a box no card belongs in.
  expect(pickerTakesScans(SKETCH_BOX)).toBe(false);
  expect(pickerTakesScans(PRODUCT_BOX)).toBe(true);
  expect(pickerTakesScans(ARTISAN_BOX)).toBe(true);
  expect(pickerTakesScans(refField({ key: "x", label: "Prototype", refModel: "DwPrototype" }))).toBe(true);

  expect(scanTypeRefusal(SKETCH_BOX, ARTISAN_CODE)).toContain("no printed code");
});

test("an out-of-scope record is SAID and never returned as a choice", () => {
  // The normal case for a WORKSHOP-scoped box handed another designer's card: the record is real and
  // readable and this field's scope excludes it. The server hands it over under `outOfScopeOption`
  // with `options` EMPTY precisely so that a client cannot show it as an ordinary row by accident.
  const outcome = scanLookupOutcome({
    field: PRODUCT_BOX,
    ref: PRODUCT_CODE,
    payload: answer({ outOfScope: true, outOfScopeOption: option(PRODUCT_CODE.id, "Blue block-printed bag") }),
    cascadeLabel: "Artisan"
  });

  // THE ASSERTION THAT IS THE WHOLE POINT: the row arrived, and it is still not a choice.
  expect(outcome.ok).toBe(false);
  if (outcome.ok) return;
  // It names the record — that is what tells the designer the right card was scanned — and it names
  // the remedy, which is the one the empty-list notice already offers rather than a second phrasing.
  expect(outcome.message).toContain("Blue block-printed bag");
  expect(outcome.message).toContain("different workshop");
  expect(outcome.message).toContain("Link that record to this workshop");
  // And it says the row was left alone, because "nothing happened" is otherwise indistinguishable
  // from a control that did not work.
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

  // BOTH possibilities in one sentence, neither confirmed. The API answers 404 for "no such record"
  // and for "not yours" on purpose — `lib/workshopCodeLookup.ts` carries the argument — and a picker
  // that told them apart would undo it one photographed card at a time.
  expect(missing.message).toContain("may not be in the repository");
  expect(missing.message).toContain("cannot open");
  expect(missing.message).toContain("artisan");

  // A cascaded box adds the third possibility, because the server's out-of-scope probe KEEPS the
  // artisan clause: a product belonging to somebody else's artisan lands here rather than in the
  // out-of-scope refusal, and "it may not be in the repository" would be a lie about a record the
  // designer can see two rows up. It gives nothing away — the filter is sent on every request this
  // box makes, so the sentence is the same for every code scanned at it.
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

  // A row that is NOT the one on the card is not accepted merely for being the only row in the
  // answer. An API that has never heard of `recordId` ignores it and returns its ordinary list; with
  // the one exception named below, taking a record out of that list would point the stage at
  // whatever happens to sort first.
  const wrongRow = scanLookupOutcome({
    field: PRODUCT_BOX,
    ref: PRODUCT_CODE,
    payload: answer({ options: [option("cmsik2jg8000eh8xc1lcy999z", "Another artisan's bag")] }),
    cascadeLabel: "Artisan"
  });
  expect(wrongRow.ok).toBe(false);

  // THE EXCEPTION: a prototype tag printed before the row ever reached the server carries its
  // `_clientKey`, and the server matches either spelling while answering with the row's server id.
  // So the one option a NARROWED answer holds is the row that was scanned — and `truncated` is what
  // says it was narrowed, which is why the request asks for a page of one.
  const prototypeBox = refField({ key: "prototypeRef", label: "Prototype", refModel: "DwPrototype" });
  const byClientKey: WorkshopCodeRef = { recordType: "prototype", id: "b7b1f6e0-0a54-4f0e-9d5f-2c1f3a4b5c6d" };
  const local = scanLookupOutcome({
    field: prototypeBox,
    ref: byClientKey,
    payload: answer({ model: "DwPrototype", options: [option("cmsik2jg8000eh8xc1lcy663c", "Bag, second try")] }),
    cascadeLabel: ""
  });
  expect(local.ok).toBe(true);

  // …and not out of a list the server plainly did not narrow.
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

/* ────────────────────────────────────────────────────────────────────────────
 * …and whether the answer may still be written when it finally arrives
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * WHY THE COMMIT IS TESTED SEPARATELY FROM THE LOOKUP, AND WHY IT IS TESTED AT ALL.
 *
 * The five tests above ask what the picker DECIDED about a code. These ask whether that decision may
 * still be acted on by the time it comes back — a different question with a different answer, and
 * the one that shipped broken. The check used to live inside the component and compare the cascade
 * value the lookup ran under with the same render's `filterValue`, which is the variable it had just
 * been copied from: a branch that could not be taken, guarding a hazard that was wide open. It is
 * wide open because `WorkshopCodeScanner`'s camera loop re-arms itself frame by frame, so on the
 * camera route — the one used in the room — the callbacks are frozen at the render "Scan" was
 * pressed, for the whole session.
 *
 * The pure function is the whole judgement, so these drive it exactly as the component does: a stash
 * stamped with the cascade value the REQUEST went out under, and the value as it is at COMMIT time
 * handed in separately. A test cannot press Scan and change a dropdown — there is no React renderer
 * here — but it can prove that the two values are compared and that a difference refuses.
 */
const HELD = (filter: string, id = PRODUCT_CODE.id): ScanHeld => ({
  id,
  filter,
  option: option(id, "Blue block-printed bag")
});

/** What the lookup handed the scanner: found, and saying only what it found. */
const FOUND = { ok: true, label: "Blue block-printed bag" } as const;

test("a scan answered after the row's artisan changed is refused, and nothing is written", () => {
  // Stage 6, the failure in full: the reader is open on "Documented product", the designer changes
  // the artisan on the row from one person to another, and the card of the FIRST artisan's product
  // is held up. The lookup went out under the old artisan and found their product. This is the last
  // place that can stop it landing on a row that now names somebody else — the report attributing
  // one artisan's work to another, with nothing on screen having said so.
  const stale = scanCommitDecision({
    held: HELD("artisan-kamla"),
    ref: PRODUCT_CODE,
    resolution: FOUND,
    filterValue: "artisan-rekha",
    field: PRODUCT_BOX
  });
  expect(stale.commit).toBeNull();
  // Said, not swallowed: "nothing happened" is otherwise indistinguishable from a reader that is not
  // working, and the designer's next act is to scan the same card again — which now asks the
  // question the row is asking, because the request reads the cascade live too.
  expect(stale.notice).toContain("changed while that code was being looked up");
  expect(stale.notice).toContain("Nothing on this row has been changed");

  // AND THE SAME CALL COMMITS WHEN THE ROW HAS NOT MOVED, which is the half that would catch the
  // guard being "fixed" into refusing everything.
  const fresh = scanCommitDecision({
    held: HELD("artisan-kamla"),
    ref: PRODUCT_CODE,
    resolution: FOUND,
    filterValue: "artisan-kamla",
    field: PRODUCT_BOX
  });
  expect(fresh.commit?.id).toBe(PRODUCT_CODE.id);

  // A box with no cascade at all has "" on both sides and is never held back by this.
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
  // `WorkshopCodeScanner` renders the lookup's answer in its `role="status"` block BEFORE it calls
  // the commit, so a lookup that announced "chosen for this row" would leave that sentence standing
  // as the only thing said on every path where the commit then writes nothing. The lookup reports
  // what the code NAMES; this reports what the row DID.
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

  // The other half of the same rule: a REFUSAL adds no second sentence, because the scanner has
  // already said why in its own block and two copies of one refusal is the control arguing with
  // itself. Nothing is written either way.
  const refused = scanCommitDecision({
    held: null,
    ref: PRODUCT_CODE,
    resolution: { ok: false, message: "That code names an artisan…" },
    filterValue: "artisan-kamla",
    field: PRODUCT_BOX
  });
  expect(refused.commit).toBeNull();
  expect(refused.notice).toBeNull();

  // An answer to a DIFFERENT code than the one being committed — a second scan overtaking the first
  // — is dropped on the id and not on the timing. The stash holds one record; the commit is told
  // which one it is for.
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
  // A spelling pin rather than a rule, and it is here because the rule itself is one line of one
  // component and cannot be executed: `resolveScan` returns a `detail` that the scanner prints
  // before the commit has decided anything. It carried "Chosen for “…” on this row." and that
  // sentence was false on every refusing path below it. If it comes back, this fails.
  expect(codeOf("components/designworkshop/StageReferenceField.tsx")).not.toContain("Chosen for");
});
