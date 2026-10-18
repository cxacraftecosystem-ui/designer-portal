import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { artisanCardPdfPath, maskedLast4, qrImageSrc, REISSUE_REASONS } from "@/lib/artisanCardApi";

/**
 * The artisan card panel and the two bulk-print surfaces: the print buttons reach the server's PDF
 * routes, Reissue confirms with a reason first, and nothing is drawn while cards are off. No browser
 * (there is no React renderer in devDependencies), so the wiring is read off the source and the pure
 * halves are called.
 */
const read = (path: string) => readFileSync(join(__dirname, "..", path), "utf8");
const PANEL = read("components/artisans/ArtisanCardPanel.tsx");
const SHEET = read("components/artisans/ArtisanCardSheetActions.tsx");
const API = read("lib/artisanCardApi.ts");

test("Print card and Print A6 ask the single-card PDF route at the two sizes", () => {
  expect(artisanCardPdfPath("a1", "id1", true)).toBe("/artisans/a1/card.pdf?size=id1&photo=1&source=WEB");
  expect(artisanCardPdfPath("a 1", "a6", false)).toBe("/artisans/a%201/card.pdf?size=a6&photo=0&source=WEB");
  expect(PANEL).toContain('onClick={() => void print("id1")}');
  expect(PANEL).toContain('onClick={() => void print("a6")}');
  expect(PANEL).toContain("downloadArtisanCard(artisanId, size, photo)");
  // One row of three buttons through ActionGroup, Reissue last and marked destructive.
  expect(PANEL).toMatch(/<ActionGroup>[\s\S]*Print card[\s\S]*Print A6[\s\S]*data-danger[\s\S]*Reissue[\s\S]*<\/ActionGroup>/);
  // The photo switch is the shared Toggle, not a checkbox.
  expect(PANEL).toContain('<Toggle label="Include photo"');
});

test("Reissue only opens a confirmation; the request is sent from the dialog, with a reason", () => {
  expect(PANEL).toContain("onClick={() => setAsking(true)}");
  expect(PANEL).toMatch(/<FieldDialog[\s\S]*tone="danger"[\s\S]*disabled=\{!reason \|\| busy === "reissue"\}[\s\S]*onClick=\{\(\) => void reissue\(\)\}/);
  expect(PANEL).toContain("reissueArtisanCard(artisanId, reason)");
  expect(REISSUE_REASONS.map((row) => row.value)).toEqual(["LOST", "DAMAGED", "OTHER"]);
  expect(API).toContain("/card/reissue");
});

test("the panel and the sheet draw nothing until the server says cards are on", () => {
  expect(PANEL).toContain("if (!enabled) return null;");
  expect(SHEET).toContain("if (!enabled) return null;");
  expect(API).toContain('"/artisan-cards/status"');
});

test("the bulk print posts one sheet request to the server", () => {
  expect(API).toContain('fetchFile("/artisan-cards/sheet.pdf"');
  expect(SHEET).toContain("downloadArtisanCardSheet(source, size, photo)");
  const codes = read("app/(protected)/design-workshops/[id]/codes/page.tsx");
  expect(codes).toContain("source={rosterRows.length ? { designWorkshopId: serverWorkshopId } : null}");
  expect(codes).toContain('primaryLabel="Download artisan cards (PDF)"');
  const list = read("app/(protected)/artisans/page.tsx");
  expect(list).toContain("<ArtisanCardSheetActions source={{ artisanIds: ticked }}");
});

test("the QR is shown as an image of the server's SVG, and the face masks the Aadhaar", () => {
  expect(qrImageSrc('<svg a="1"/>')).toBe("data:image/svg+xml;charset=utf-8,%3Csvg%20a%3D%221%22%2F%3E");
  expect(PANEL).not.toContain("dangerouslySetInnerHTML");
  expect(maskedLast4("9012")).toBe("XXXX XXXX 9012");
  expect(maskedLast4(null)).toBeNull();
});
