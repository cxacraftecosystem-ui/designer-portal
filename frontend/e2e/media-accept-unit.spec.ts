import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { inferMediaType } from "@/lib/media";

const ROOT = join(__dirname, "..");
const CARD = readFileSync(join(ROOT, "components", "forms", "MediaCaptureField.tsx"), "utf8");

function acceptList(name: string): string[] {
  const match = CARD.match(new RegExp(`const ${name} = "([^"]*)";`));
  expect(match, `${name} must be a single string literal this test can read`).not.toBeNull();
  return (match as RegExpMatchArray)[1].split(",");
}

const asFile = (name: string, type: string) => new File([new Uint8Array([1])], name, { type });

test("a FILE field's chooser offers every format the registry names for one", () => {
  const image = acceptList("imageAccept");
  const document = acceptList("documentAccept");

  expect(inferMediaType(asFile("line-art.svg", "image/svg+xml"))).toBe("IMAGE");
  expect(image, "sketch.lineArtFile asks for an SVG").toContain(".svg");
  expect(document, "an SVG is an IMAGE to inferMediaType, so the two lists must not disagree").not.toContain(".svg");

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

  expect(inferMediaType(asFile("consent.pdf", "application/pdf"))).toBe("PDF");
  expect(document, "a FILE field must go on offering a scanned PDF").toContain(".pdf");

  expect(inferMediaType(asFile("clip.webm", "audio/webm"))).toBe("AUDIO");
  expect(inferMediaType(asFile("clip.webm", "video/webm"))).toBe("VIDEO");
  expect(audio, "audio/webm is a real recording container").toContain(".webm");
  expect(video, "video/webm is a real recording container").toContain(".webm");

  expect(image).not.toContain(".pdf");
  expect(image).not.toContain(".webm");
});

test("no field narrows to DOCUMENT or PDF, which is what makes the .pdf placement safe", () => {
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
  expect(inferMediaType(asFile("prototype.glb", ""))).toBe("DOCUMENT");
  const media = readFileSync(join(ROOT, "lib", "media.ts"), "utf8");
  expect(media, "an untyped file must still be given a non-empty mimeType before presign").toMatch(
    /\.type\s*\|\|\s*"application\/octet-stream"/
  );
});
