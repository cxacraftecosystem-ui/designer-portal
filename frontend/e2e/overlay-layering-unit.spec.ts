import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const codeOnly = (relative: string) =>
  read(relative)
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/(^|[^:])\/\/.*$/gm, "$1");

const APP_SHELL = "components/AppShell.tsx";
const SELVEDGE = "components/PageSelvedge.tsx";
const LIGHTBOX = "components/media/MediaLightbox.tsx";
const EDITOR = "components/designworkshop/RichTextEditor.tsx";
const DIALOG = "components/dialogs/FieldDialog.tsx";
const NAV = "components/DynamicIslandNav.tsx";
const UPLOAD_TRAY = "components/media/UploadTray.tsx";
const ARTISANS = "app/(protected)/artisans/page.tsx";
const REPORT = "app/(protected)/design-workshops/[id]/report/page.tsx";
const SCROLL_LOCK = "lib/scrollLock.ts";

test("main keeps its positioning and loses its z-index", () => {
  const shell = read(APP_SHELL);
  expect(shell).toContain(
    'className="relative mx-auto max-w-7xl px-4 pb-[calc(var(--bottom-nav-h,env(safe-area-inset-bottom,0px))+1.5rem)] pt-[calc(60px+1.25rem)] lg:pb-12 lg:pt-24"'
  );
  expect(shell).not.toMatch(/className="relative z-\S+ mx-auto max-w-7xl/);
});

test("the fixed full-screen surfaces sit at the dialog rung, above the island's 50", () => {
  expect(read(LIGHTBOX)).toContain('className="fixed inset-0 z-100 grid place-items-center bg-black/70 p-4"');
  expect(read(LIGHTBOX)).not.toContain('className="fixed inset-0 z-50 grid place-items-center');
  expect(read(EDITOR)).toContain('"fixed inset-0 z-100 flex flex-col gap-2 overflow-y-auto bg-bg-0 p-4 sm:p-6"');
});

test("the nav sheet's scrim out-ranks the fixed chrome a page can mount", () => {
  expect(read(NAV)).toContain('className="nav-sheet-overlay fixed inset-0 z-90"');
  expect(read(UPLOAD_TRAY)).toContain('className="pointer-events-none fixed inset-x-0 bottom-0 z-40 px-4 pt-3"');
  expect(read(NAV)).not.toMatch(/nav-sheet-overlay fixed inset-0 z-\[?1\d\d/);
});

test("PageSelvedge names the island's real rung", () => {
  const selvedge = read(SELVEDGE);
  expect(selvedge).not.toMatch(/The nav island is `z-\[?60\]?`/);
  expect(read(NAV)).toContain("fixed inset-x-0 top-3 z-50 flex justify-center");
});

test("the Name cell offers the row's action as a focusable link", () => {
  const page = read(ARTISANS);
  expect(page).toContain("<OpenableRow key={artisan.id} href={RECORD_VIEW_HREF.artisan(artisan.id)}>");
  expect(page).toContain("<OpenableRowLink href={RECORD_VIEW_HREF.artisan(artisan.id)}>{artisan.name}</OpenableRowLink>");
});

test("the row no longer selects, so there is no aria-expanded left to keep", () => {
  expect(read(ARTISANS)).not.toContain("selectedArtisan");
});

test("the row keeps its implicit row role", () => {
  expect(codeOnly(ARTISANS)).not.toContain('role="button"');
});

test("the three carry-forward launchers are already links, and stay links", () => {
  expect(read("app/(protected)/artisans/[id]/page.tsx")).toContain("<Link key={item.href} href={item.href}");
});

test("the workshop read clears the spinner when it fails", () => {
  const page = read(REPORT);
  expect(page).toContain('setError(err instanceof Error ? err.message : "Unable to load this design workshop");');
  expect(page).toContain("        setPreviewing(false);\n        setPreviewFailed(true);");
  expect(page).toContain("  }, [remoteId, retryToken, adoptDraftForReport]);");
});

test("a workshop that names no template does not spin either", () => {
  expect(read(REPORT)).toContain("if (!seeded) setPreviewing(false);");
});

test("a read that succeeds takes down the banner its own failure raised", () => {
  expect(read(REPORT)).toContain("        setError(null);\n        setRegistry(nextRegistry);");
});

test("the panel has a building state, a failed state with a retry, and an empty state", () => {
  const page = read(REPORT);
  expect(page).toContain('<p className="text-sm text-ink-700">Building the preview…</p>');
  expect(page).toContain("          ) : previewFailed ? (");
  expect(page).toContain('<button type="button" className="field-button-secondary" onClick={retryPreview}>');
  expect(page).toContain('<p className="text-sm text-ink-700">No preview available.</p>');
  expect(page).not.toContain('{previewing ? "Building the preview…" : "No preview available."}');
});

test("the header description stops saying Loading for the life of the tab", () => {
  const page = read(REPORT);
  expect(page).toContain("This workshop couldn't be loaded. Use Try again at the foot of the page.");
  expect(page).toContain('"Built on this device from everything saved on it."');
});

test("the lock lives in one module and locks the root element", () => {
  const source = read(SCROLL_LOCK);
  expect(source).toContain("export function lockPageScroll()");
  expect(source).toContain("export function unlockPageScroll()");
  expect(source).toContain('root.classList.add("nav-scroll-locked")');
  expect(source).toContain('root.style.setProperty("--nav-scroll-gutter", `${gutter}px`)');
  expect(source).toContain("holders += 1;\n  if (holders > 1) return;");
  expect(source).toContain("holders = Math.max(0, holders - 1);\n  if (holders > 0) return;");
});

test("all three owners go through it and none touches the class itself", () => {
  for (const owner of [DIALOG, NAV, EDITOR]) {
    const source = read(owner);
    expect(source, owner).toContain('import { lockPageScroll, unlockPageScroll } from "@/lib/scrollLock";');
    expect(source, owner).toContain("lockPageScroll()");
    expect(source, owner).not.toContain('classList.add("nav-scroll-locked")');
    expect(source, owner).not.toContain('classList.remove("nav-scroll-locked")');
  }
});

test("FieldDialog no longer locks overflow on the body", () => {
  const dialog = codeOnly(DIALOG);
  expect(dialog).not.toContain("document.body.style.overflow");
  expect(dialog).not.toContain("previousBodyOverflow");
});
