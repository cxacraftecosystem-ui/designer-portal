import { readFileSync, readdirSync } from "node:fs";
import { join, relative, resolve, sep } from "node:path";

import { expect, test } from "@playwright/test";

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
  //
  // `RecordCodeScanPanel` is counted as a surface in its own right because it is what `/search`
  // mounts; the page below is asserted to mount the panel, so the chain is complete.
  const mounts = filesUsing(/<WorkshopCodeScanner\b/);
  expect(mounts.sort()).toEqual(
    ["app/(protected)/design-workshops/[id]/codes/page.tsx", "components/RecordCodeScanPanel.tsx"].sort()
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
