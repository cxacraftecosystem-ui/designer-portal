import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { inferMediaType } from "@/lib/media";

/**
 * WHAT THE FILE CHOOSER OFFERS, MEASURED AGAINST WHAT THE REGISTRY ASKS FOR.
 *
 * ── THE DEFECT THIS PINS ──────────────────────────────────────────────────────────────────────
 * `MediaCaptureField` builds a `<input type="file" accept=…>` out of four extension lists, and a
 * FILE field's chooser is all four joined. The registry declares `prototype.modelFile` as a FILE
 * field labelled "3D model" — and no list mentioned a 3D model, nor can the three `type/*`
 * wildcards reach one: a browser with no mapping for `.glb` reports an empty type, which is the
 * case `lib/media`'s untyped-file fallback exists for. The box was declared, drawn on screen, and
 * unanswerable: the chooser would not let the file be selected. Android never had the problem
 * (`DwMediaCapture.kt`'s `galleryMimeFor` answers the match-anything wildcard for every field that
 * is not IMAGE/VIDEO/AUDIO), so this was a web-only dead end.
 *
 * ── AND THE RULE THAT KEEPS A FIX FROM BECOMING A WORSE BUG ───────────────────────────────────
 * The four lists are the CHOOSER'S BUCKETS, and a token must sit in a bucket whose `allowedTypes`
 * will admit the file it matches. `addFiles` puts every incoming file back through `inferMediaType`
 * against the caller's `allowedTypes`, so an extension advertised to a NARROWED field that then
 * rejects it is offered by the chooser and DROPPED SILENTLY — which reads as the app losing a file
 * rather than refusing it. `.svg` is the one that catches this out: `image/svg+xml` starts with
 * `image/`, so it is an IMAGE to `inferMediaType` however much it feels like a document.
 *
 * THE RULE HAS TWO STANDING EXCEPTIONS AND THIS FILE PINS BOTH, because the version of it that
 * admitted none was false about the very file it headed and would have talked a later reader into
 * deleting `.pdf`:
 *
 *  * `.pdf` lives in `documentAccept` although `inferMediaType` answers `"PDF"`, because that list
 *    is the FILE field's attachment list — a FILE field passes no `allowedTypes`, so nothing filters
 *    what it admits — and `ACCEPT_BY_TYPE.PDF` is a separate narrow slot no caller reaches. The
 *    third test below pins the premise that keeps this safe.
 *  * `.webm` is in `audioAccept` AND `videoAccept`, because the container carries either and the
 *    chooser only ever sees the extension.
 */

const ROOT = join(__dirname, "..");
const CARD = readFileSync(join(ROOT, "components", "forms", "MediaCaptureField.tsx"), "utf8");

function acceptList(name: string): string[] {
  const match = CARD.match(new RegExp(`const ${name} = "([^"]*)";`));
  expect(match, `${name} must be a single string literal this test can read`).not.toBeNull();
  return (match as RegExpMatchArray)[1].split(",");
}

/** A file the way the chooser hands one over: a name, and whatever type the platform could give. */
const asFile = (name: string, type: string) => new File([new Uint8Array([1])], name, { type });

test("a FILE field's chooser offers every format the registry names for one", () => {
  const image = acceptList("imageAccept");
  const document = acceptList("documentAccept");

  /*
    `sketch.lineArtFile` — "An SVG or vector export, if one was produced". It was reachable through
    the leading `image/*` on any platform that maps the extension to `image/svg+xml`; the token is
    for the platforms that do not, where a file matching neither the wildcard nor any extension
    cannot be picked at all.

    NOT IN THE DOCUMENT LIST, which is where it looks like it belongs — and this is the classifying
    call made rather than restated, so the placement is pinned to behaviour: an IMAGE-narrowed field
    admits it, and an SVG advertised to a DOCUMENT-narrowed field would be offered and then thrown
    away without a word.
  */
  expect(inferMediaType(asFile("line-art.svg", "image/svg+xml"))).toBe("IMAGE");
  expect(image, "sketch.lineArtFile asks for an SVG").toContain(".svg");
  expect(document, "an SVG is an IMAGE to inferMediaType, so the two lists must not disagree").not.toContain(".svg");

  /*
    `prototype.modelFile` — "3D model". In the DOCUMENT list because that is the FILE field's
    attachment list, and because a file the browser cannot type is in any case what `inferMediaType`
    calls a DOCUMENT. There is no 3D member of `MediaType` on either side of the wire.
  */
  expect(inferMediaType(asFile("prototype.glb", ""))).toBe("DOCUMENT");
  for (const extension of [".glb", ".gltf"]) {
    expect(document, `prototype.modelFile cannot be answered without ${extension}`).toContain(extension);
  }
  expect(image).not.toContain(".glb");
});

test("the two standing exceptions to the placement rule are the two that are argued", () => {
  const image = acceptList("imageAccept");
  const audio = acceptList("audioAccept");
  const video = acceptList("videoAccept");
  const document = acceptList("documentAccept");

  /*
    `.pdf` IS THE ONE A LITERAL READING OF THE RULE WOULD DELETE, so it is pinned here with the
    reason. `inferMediaType` answers `"PDF"` and not `"DOCUMENT"`, yet the token belongs in
    `documentAccept`: that list is what a FILE field's chooser joins, a FILE field narrows nothing,
    and removing it would stop every FILE field offering the scanned consent form that
    `ALLOWED_TYPES`' own comment in `components/designworkshop/FieldInput.tsx` says must stay
    pickable. Both halves are asserted so neither can be "tidied" alone.
  */
  expect(inferMediaType(asFile("consent.pdf", "application/pdf"))).toBe("PDF");
  expect(document, "a FILE field must go on offering a scanned PDF").toContain(".pdf");

  /*
    `.webm` IS AMBIGUOUS BY EXTENSION and is deliberately in both time-based lists. Dropping it from
    either would hide half a real format from the field that wants it.
  */
  expect(inferMediaType(asFile("clip.webm", "audio/webm"))).toBe("AUDIO");
  expect(inferMediaType(asFile("clip.webm", "video/webm"))).toBe("VIDEO");
  expect(audio, "audio/webm is a real recording container").toContain(".webm");
  expect(video, "video/webm is a real recording container").toContain(".webm");

  // And no third exception has crept in: the image list still classifies to IMAGE throughout.
  expect(image).not.toContain(".pdf");
  expect(image).not.toContain(".webm");
});

test("no field narrows to DOCUMENT or PDF, which is what makes the .pdf placement safe", () => {
  /*
    THE PREMISE UNDER THE EXCEPTION ABOVE, PINNED SO IT CANNOT LAPSE SILENTLY. `.pdf` sitting in
    `documentAccept` costs nothing only while nothing passes `allowedTypes: ["DOCUMENT"]` — the
    moment something does, that field's chooser offers a PDF and `addFiles` drops it on the floor,
    which is exactly the silent loss the rule exists to prevent. `ALLOWED_TYPES` is the one place a
    registry field type is turned into a narrowing, and it names only the three wildcard-led buckets.
  */
  const fieldInput = readFileSync(join(ROOT, "components", "designworkshop", "FieldInput.tsx"), "utf8");
  const table = fieldInput.match(/const ALLOWED_TYPES[^=]*=\s*\{[\s\S]*?\n\};/);
  expect(table, "ALLOWED_TYPES must stay a readable object literal").not.toBeNull();
  const declared = (table as RegExpMatchArray)[0];
  for (const narrowing of ["DOCUMENT", "PDF", "OTHER"]) {
    expect(
      declared,
      `${narrowing} narrowing would make documentAccept's .pdf a silent drop — see the rule in MediaCaptureField`
    ).not.toContain(narrowing);
  }
});

test("nothing on the server has to be taught these types", () => {
  /*
    MEASURED RATHER THAN ASSUMED, because the obvious worry is a 422 on a file the browser cannot
    type. `POST /media/presign` takes `mimeType: str = Field(min_length=1, …)` and signs whatever it
    is given — there is no allowlist anywhere in `backend/app/api/routes/media.py` — and the browser
    never sends the empty string a `.glb` would otherwise produce, because the upload substitutes a
    generic binary type.

    THE SUBSTITUTION IS PINNED AS A BEHAVIOUR AND NOT AS A LINE OF SOURCE. An earlier version of
    this test asserted the exact text `const mimeType = file.type || "application/octet-stream";`
    out of `lib/media.ts` — a file this group does not own and another group is editing — so a
    rename of that local or a move into a helper would have turned this group's suite red for a
    change that broke nothing. `uploadObject` is not exported, so what is checked is the same fact
    from the two sides that ARE reachable: an untyped file classifies as DOCUMENT (so it is not
    silently reclassified on the way up), and the fallback literal still exists somewhere in the
    upload path, matched loosely enough to survive a rename.
  */
  expect(inferMediaType(asFile("prototype.glb", ""))).toBe("DOCUMENT");
  const media = readFileSync(join(ROOT, "lib", "media.ts"), "utf8");
  expect(media, "an untyped file must still be given a non-empty mimeType before presign").toMatch(
    /\.type\s*\|\|\s*"application\/octet-stream"/
  );
});
