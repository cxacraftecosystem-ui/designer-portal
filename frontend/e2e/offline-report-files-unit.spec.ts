import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { join } from "node:path";

import { expect, test } from "@playwright/test";
import JSZip from "jszip";

import type { OfflineReportInput } from "@/lib/offlineReport/assemble";
import type { Registry } from "@/lib/offlineReport/builder";
import type { LoadedImage } from "@/lib/offlineReport/docx";
import { generateOfflineFile, type OfflineWriters } from "@/lib/offlineReport/generate";
import type { PdfMakeLike } from "@/lib/offlineReport/pdf";

/**
 * THE FILES A BROWSER WRITES WITH NO CONNECTION OPEN, AND SAY WHAT THE WORKSHOP SAYS.
 *
 * The parity spec beside this proves the browser builds the server's document; this proves the two
 * writers turn that document into real files — a .docx Word can unzip and a .pdf with pages and the
 * fonts embedded — from the shared fixture workshop, through `generateOfflineFile`, which is the one
 * door the report screen uses. pdfmake's Node build stands in for its browser build (the same
 * layout code), the fonts are the files the app serves from `public/report-fonts`, and photographs
 * and figures are a one-pixel PNG, because decoding images is the browser's job and is not what is
 * under test.
 */

const ROOT = join(__dirname, "..", "..");
const fixture = JSON.parse(readFileSync(join(ROOT, "shared", "report-parity", "workshop.json"), "utf8")) as OfflineReportInput;
const registry = JSON.parse(
  readFileSync(join(ROOT, "android", "app", "src", "main", "assets", "design-workshop-schema.json"), "utf8")
) as Registry;
const FONTS = join(__dirname, "..", "public", "report-fonts");

const PIXEL: LoadedImage = {
  data: new Uint8Array(
    Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64")
  ),
  type: "png",
  widthPx: 1,
  heightPx: 1
};

function writers(fonts: (file: string) => ArrayBuffer | null = (file) => readFileSync(join(FONTS, file)).buffer as ArrayBuffer): OfflineWriters & {
  dropped: string[];
} {
  const dropped: string[] = [];
  return {
    dropped,
    loadImage: async (image) => (image.source.endsWith("13") ? null : PIXEL),
    rasterise: async () => PIXEL,
    pdfmake: async () => {
      const require = createRequire(__filename);
      const pdfmake = require("pdfmake") as PdfMakeLike & {
        setUrlAccessPolicy(policy: () => boolean): void;
        setLocalAccessPolicy(policy: () => boolean): void;
      };
      pdfmake.setUrlAccessPolicy(() => false);
      pdfmake.setLocalAccessPolicy(() => false);
      return pdfmake;
    },
    fontSource: async (file) => {
      try {
        return fonts(file);
      } catch {
        return null;
      }
    }
  };
}

const NOW = new Date("2026-10-10T09:30:00Z");

test("the .docx is a Word document carrying the workshop's text, in both scripts", async () => {
  const file = await generateOfflineFile(registry, fixture, "DOCX", { includeTranscripts: true }, writers(), NOW);
  expect(file.fileName).toBe("DesignWorkshop_DW-OD-2026-014_20261010.docx");
  const zip = await JSZip.loadAsync(await file.blob.arrayBuffer());
  const xml = await zip.file("word/document.xml")!.async("string");
  expect(xml).toContain("Sambalpuri ikat design workshop");
  expect(xml).toContain("ସମ୍ବଲପୁରୀ ଇକତ");
  expect(xml).toContain("Annexure — Recordings and transcripts");
  expect(xml).toContain("₹ 1,20,000.00");
  // The running foot counts the pages, as every other file renderer does.
  const footers = await Promise.all(
    Object.keys(zip.files)
      .filter((name) => /word\/footer\d*\.xml$/.test(name))
      .map((name) => zip.file(name)!.async("string"))
  );
  expect(footers.join("")).toContain("NUMPAGES");
  expect(Object.keys(zip.files).some((name) => name.startsWith("word/media/"))).toBe(true);
  // The one photograph the stub could not load is named beside the download, not lost in silence.
  expect(file.warnings.join(" ")).toContain("photograph(s) are not on this device");
});

test("the .pdf has pages and embeds the Noto faces the document's scripts need", async () => {
  const file = await generateOfflineFile(registry, fixture, "PDF", {}, writers(), NOW);
  const bytes = Buffer.from(await file.blob.arrayBuffer());
  expect(bytes.subarray(0, 5).toString("latin1")).toBe("%PDF-");
  const text = bytes.toString("latin1");
  const pages = (text.match(/\/Type \/Page\b/g) ?? []).length;
  expect(pages).toBeGreaterThan(8);
  expect(text).toContain("NotoSans");
  expect(text).toContain("NotoSansOriya");
  expect(file.warnings.join(" ")).not.toContain("empty boxes");
});

test("a script whose face is not on the device is named beside the file, not silently boxed", async () => {
  const noOdia = writers((file) => {
    if (file.startsWith("NotoSansOriya")) throw new Error("absent");
    return readFileSync(join(FONTS, file)).buffer as ArrayBuffer;
  });
  const file = await generateOfflineFile(registry, fixture, "PDF", {}, noOdia, NOW);
  expect(file.warnings.join(" ")).toContain("empty boxes for text in these scripts: Odia");
});

test("with no Latin face on the device the .pdf is refused in words, and the .docx still works", async () => {
  const none = writers(() => {
    throw new Error("absent");
  });
  await expect(generateOfflineFile(registry, fixture, "PDF", {}, none, NOW)).rejects.toThrow(/The Word document can be/);
  const docx = await generateOfflineFile(registry, fixture, "DOCX", {}, none, NOW);
  expect(docx.blob.size).toBeGreaterThan(1000);
});

test("a workshop this device never fetched the sources for still builds, from the draft alone", async () => {
  const bare: OfflineReportInput = { ...fixture, sources: null };
  const file = await generateOfflineFile(registry, bare, "DOCX", { includeTranscripts: true, includeAiLayers: true }, writers(), NOW);
  const xml = await (await JSZip.loadAsync(await file.blob.arrayBuffer())).file("word/document.xml")!.async("string");
  expect(xml).toContain("Fish cushion cover");
  // Nothing was kept for the annexures, so none is printed — and nothing claims otherwise.
  expect(xml).not.toContain("Annexure — Recordings and transcripts");
  expect(xml).not.toContain("Annexure — Questionnaire responses");
});
