import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * FOUR THINGS A FULL-SCREEN SURFACE, A TABLE ROW AND A SPINNER GOT WRONG, AND THE EVIDENCE THEY ARE
 * FIXED.
 *
 * These are grouped because they are one claim — "a surface that covers the page really covers it,
 * and a page that has stopped working says so" — and because each was invisible to every other test
 * in this suite for the same reason: they are facts about the stylesheet and about which branch of a
 * ternary can ever be reached, not about what a component renders when everything works.
 *
 *  1. **Two full-screen surfaces painted UNDER the navigation island.** `AppShell`'s `<main>`
 *     carried `z-10`, which makes it a stacking context; the media lightbox and the rich-text
 *     editor's full-screen mode are drawn inside it, so both were capped at that context's level
 *     regardless of their own z-index and sat beneath a pill that still took clicks. Clicking it
 *     navigated away from a surface that had just declared `aria-modal="true"`, and `BackButton`'s
 *     leave interception does not cover the island's links, so an unsaved form went with it. The
 *     cap worked downwards too — it held `UploadTray` under the nav sheet's scrim — so taking it
 *     off meant raising that scrim to its own rung, which is the second test below.
 *  2. **The only clickable table row in this frontend was keyboard-unreachable.** `/artisans` put
 *     `onClick` on the `<tr>` with no `tabIndex`, no key handler and no focusable control carrying
 *     the same action — a WCAG 2.1.1 failure on the control that reveals the three entry launchers.
 *  3. **"Building the preview…" never resolved.** The flag was cleared in two places, both of them
 *     on paths a failed first load never reaches, and the loader that owns the `finally` is gated on
 *     a template id only the success path sets.
 *  4. **The scroll lock was unrepaid and iOS Safari ignored it.** `FieldDialog` locked `overflow` on
 *     <body>, which `globals.css` documents iOS Safari as ignoring, and paid nothing back for the
 *     scrollbar it removed, so every centred dialog shifted sideways as it opened.
 *
 * Source reads, for the reason `discarded-work-unit.spec.ts` states plainly: there is no React
 * renderer in this repository's devDependencies, so a component cannot be mounted. Every assertion
 * below names a substring the tree did not contain — or contained and must not — before the fix.
 */

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

/**
 * The same source with its comments taken out.
 *
 * Needed for the NEGATIVE assertions only, and for a reason peculiar to this repository: the house
 * style is long prose comments that name the defect they closed, so a file explaining "deliberately
 * no role=button on the row" contains the very string a test asserting its absence is looking for.
 * Both negative tests below failed on their own documentation before this stripper existed. The
 * `[^:]` guard on the line-comment pattern is what keeps `https://` out of it.
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The shell's <main> is no longer a stacking context
 * ──────────────────────────────────────────────────────────────────────────── */

test("main keeps its positioning and loses its z-index", () => {
  const shell = read(APP_SHELL);
  // `relative` must stay: the selvedge is `fixed z-0` and only tree order puts the page above it.
  expect(shell).toContain('className="relative mx-auto max-w-7xl px-4 pb-12 pt-24"');
  // Any z-index utility here — not only the `z-10` that shipped — re-creates the trap.
  expect(shell).not.toMatch(/className="relative z-\S+ mx-auto max-w-7xl/);
});

test("the fixed full-screen surfaces sit at the dialog rung, above the island's 50", () => {
  // The lightbox declares `aria-modal`; it used to share the island's rung and lose on source order.
  expect(read(LIGHTBOX)).toContain('className="fixed inset-0 z-[100] grid place-items-center bg-black/70 p-4"');
  expect(read(LIGHTBOX)).not.toContain('className="fixed inset-0 z-50 grid place-items-center');
  expect(read(EDITOR)).toContain('"fixed inset-0 z-[100] flex flex-col gap-2 overflow-y-auto bg-bg-0 p-4 sm:p-6"');
});

/**
 * The other half of taking the cap off `main`, and the one the first pass missed.
 *
 * `z-10` on `<main>` held the page's own fixed chrome down as well as the two full-screen surfaces.
 * `UploadTray` is `fixed … z-40` and renders inside a page; the nav sheet's modal scrim was `z-40`
 * too, so with the cap gone they met as equals in the root stacking context — and equals are
 * resolved by tree order, which the nav loses, being rendered before `<main>` in `AppShell`. An
 * upload in flight would have painted its dock over an open `aria-modal` sheet, undimmed and
 * clickable. The scrim now sits at 90: above anything a page mounts, below the 100 dialog rung.
 */
test("the nav sheet's scrim out-ranks the fixed chrome a page can mount", () => {
  expect(read(NAV)).toContain('className="nav-sheet-overlay fixed inset-0 z-[90]"');
  // The rung it must clear. If the tray is ever raised, this pairing is what catches it.
  expect(read(UPLOAD_TRAY)).toContain('className="pointer-events-none fixed inset-x-0 bottom-0 z-40 px-4 pt-3"');
  // …and it must stay under the dialog rung, or a lightbox opened from a page would be dimmed by it.
  expect(read(NAV)).not.toContain("nav-sheet-overlay fixed inset-0 z-[1");
});

test("PageSelvedge names the island's real rung", () => {
  const selvedge = read(SELVEDGE);
  // The island is `z-50` (DynamicIslandNav); `z-[60]` is the skip link in AppShell.
  expect(selvedge).not.toContain("The nav island is `z-[60]`");
  expect(selvedge).toContain("is `z-50` and the skip link `z-[60]`");
  expect(read(NAV)).toContain("fixed inset-x-0 top-3 z-50 flex justify-center");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The artisan row has a real control
 * ──────────────────────────────────────────────────────────────────────────── */

test("the Name cell offers the row's action as a focusable button", () => {
  const page = read(ARTISANS);
  expect(page).toContain('<button\n                          type="button"');
  expect(page).toContain("aria-expanded={selectedArtisan?.id === artisan.id}");
  expect(page).toContain("aria-controls={selectedArtisan?.id === artisan.id ? LAUNCH_PANEL_ID : undefined}");
  expect(page).toContain("<section id={LAUNCH_PANEL_ID}");
});

test("the Name button really toggles, so aria-expanded is not a promise it cannot keep", () => {
  // Nothing else in the page clears `selectedArtisan`; a select-only button would announce
  // "expanded" for the rest of the session and then do nothing when pressed.
  expect(read(ARTISANS)).toContain(
    "setSelectedArtisan((current) => (current?.id === artisan.id ? null : artisan));"
  );
});

test("the row keeps its implicit row role", () => {
  // `role="button"` on the <tr> would fix the keyboard and orphan all seven cells for a reader.
  expect(codeOnly(ARTISANS)).not.toContain('role="button"');
});

test("the three carry-forward launchers are already links, and stay links", () => {
  // Reported as pointer-only in the audit; they are <Link>s, which render focusable anchors. Pinned
  // so a later refactor to a <div onClick> is caught here rather than re-reported as a finding.
  expect(read(ARTISANS)).toContain("<Link key={item.href} href={item.href}");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The preview panel resolves, three ways
 * ──────────────────────────────────────────────────────────────────────────── */

test("the workshop read clears the spinner when it fails", () => {
  const page = read(REPORT);
  expect(page).toContain('setError(err instanceof Error ? err.message : "Unable to load this design workshop");');
  expect(page).toContain("        setPreviewing(false);\n        setPreviewFailed(true);");
  // Re-runnable: without this dep "Try again" has nothing to re-fire, `remoteId` never changing.
  expect(page).toContain("  }, [remoteId, retryToken]);");
});

test("a workshop that names no template does not spin either", () => {
  expect(read(REPORT)).toContain("if (!seeded) setPreviewing(false);");
});

test("a read that succeeds takes down the banner its own failure raised", () => {
  // Otherwise a retry on a workshop naming no template lands on "No preview available." with the
  // previous attempt's red "could not be loaded" still at the top: two verdicts on one good load.
  // `loadPreview` clears the banner too, but on this branch it never runs.
  expect(read(REPORT)).toContain("        setError(null);\n        setRegistry(nextRegistry);");
});

test("the panel has a building state, a failed state with a retry, and an empty state", () => {
  const page = read(REPORT);
  expect(page).toContain('<p className="text-sm text-ink-700">Building the preview…</p>');
  expect(page).toContain("          ) : previewFailed ? (");
  expect(page).toContain('<button type="button" className="field-button-secondary" onClick={retryPreview}>');
  expect(page).toContain('<p className="text-sm text-ink-700">No preview available.</p>');
  // The old two-state ternary, which is what made "No preview available." the report of a failure.
  expect(page).not.toContain('{previewing ? "Building the preview…" : "No preview available."}');
});

test("the header description stops saying Loading for the life of the tab", () => {
  const page = read(REPORT);
  expect(page).toContain("This workshop could not be loaded. The panel at the foot of the page offers a retry.");
  expect(page).toContain("This workshop has not reached the repository yet, and the report is written by the server");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. One refcounted scroll lock, on <html>, with the gutter repaid
 * ──────────────────────────────────────────────────────────────────────────── */

test("the lock lives in one module and locks the root element", () => {
  const source = read(SCROLL_LOCK);
  expect(source).toContain("export function lockPageScroll()");
  expect(source).toContain("export function unlockPageScroll()");
  expect(source).toContain('root.classList.add("nav-scroll-locked")');
  expect(source).toContain('root.style.setProperty("--nav-scroll-gutter", `${gutter}px`)');
  // Refcounted: the lock comes off only when the LAST holder lets go.
  expect(source).toContain("holders += 1;\n  if (holders > 1) return;");
  expect(source).toContain("holders = Math.max(0, holders - 1);\n  if (holders > 0) return;");
});

test("all three owners go through it and none touches the class itself", () => {
  for (const owner of [DIALOG, NAV, EDITOR]) {
    const source = read(owner);
    expect(source, owner).toContain('import { lockPageScroll, unlockPageScroll } from "@/lib/scrollLock";');
    expect(source, owner).toContain("lockPageScroll()");
    // Writing the class here is what let a globally-mounted dialog closing strip it out from under
    // an open sheet or a full-screen editor.
    expect(source, owner).not.toContain('classList.add("nav-scroll-locked")');
    expect(source, owner).not.toContain('classList.remove("nav-scroll-locked")');
  }
});

test("FieldDialog no longer locks overflow on the body", () => {
  const dialog = codeOnly(DIALOG);
  expect(dialog).not.toContain("document.body.style.overflow");
  expect(dialog).not.toContain("previousBodyOverflow");
});
