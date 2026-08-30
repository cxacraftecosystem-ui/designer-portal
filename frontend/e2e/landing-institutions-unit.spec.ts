import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * THE INSTITUTIONAL MARKS AND OUTBOUND LINKS ON THE PUBLIC LANDING PAGE, AND THE FOUR SILENT WAYS
 * THEY BREAK.
 *
 * ── WHY THIS FILE EXISTS ────────────────────────────────────────────────────────────────────────
 *
 * Everything the landing page said before this wave was TEXT and lucide icons — `git ls-files` over
 * `frontend/public` returned three boundary data files and not one image. Requirements 15, 16 and 17
 * put the first real picture assets on the page: `iit-kharagpur.svg` and `dc-handicrafts.png`, each
 * rendered on two separate surfaces (the hero masthead corners and the colophon band above the
 * footer), plus three outbound destinations. Every failure mode that arrives with them is SILENT:
 *
 *   1. A MISSING FILE BREAKS EACH MARK DIFFERENTLY, AND NEITHER WAY REACHES A LOG. Both marks are
 *      `alt=""` by design — the accessible name lives on the anchor, which is the correct
 *      construction and is argued at length in `HeroLanding.tsx`. Renaming a file in
 *      `public/logos/`, or moving the page's `src` and not the file, is invisible to every existing
 *      check in this repository, which is what the two filesystem assertions below exist for.
 *
 *      ⚠ THIS PARAGRAPH USED TO STATE BOTH FAILURES BACKWARDS, and both halves were measured
 *      rather than reasoned about — Chromium, the marks aborted at the network layer, screenshots
 *      taken. It read "a 404 on the image produces no broken-image glyph and no alt text: the
 *      anchor collapses to an empty box and the page looks FINE." It does not. Chromium paints its
 *      broken-image glyph INSIDE the cream plate at the masthead's top-left corner: `alt=""`
 *      suppresses that glyph only for an image with no intrinsic box, and this `<img>` carries
 *      `width`/`height` attributes on purpose, so the box exists and the glyph is drawn in it.
 *      (Firefox does collapse it, which is presumably where the claim came from.)
 *
 *      ⚠ AND IT READ "a mask whose source 404s masks nothing, so a missing file there paints a
 *      solid white rectangle into the top-right corner." The seal fails the OPPOSITE way, and it is
 *      the one that is genuinely silent: a `mask-image` whose source never arrives masks the box
 *      out completely — checked both mid-flight, with the file delayed four seconds, and after an
 *      abort — so the corner is simply empty. A pending or failed mask is not the same event as an
 *      ABSENT mask property, and this file had them recorded as the same outcome; see failure 4,
 *      which is the case that really does paint the white rectangle.
 *
 *      So the loud failure is the PNG and the silent one is the seal, which is the reverse of what
 *      a reader was being told to expect. Neither is caught by a rendering check, and that is still
 *      the reason this file reads the filesystem instead.
 *
 *   2. AN ORPHAN FILE IN `public/logos/` IS A MARK WAITING TO BE MISREAD. This is not hypothetical:
 *      `centre-of-excellence.svg` sat there unreferenced, and its geometry was `app/icon.svg`
 *      VERBATIM — this product's own eight-point terracotta star — under
 *      `aria-label="Centre of Excellence"`. Nothing rendered it, so nothing caught it; the next
 *      editor to see three files in a logos folder and two marks on the page would have "fixed" the
 *      omission by wiring it up, and the colophon whose entire job is provenance would have carried
 *      the Design Prototype Workshop's own mark under another institution's name. A wrong mark is
 *      far more expensive than a missing one, because a missing logo is visibly missing and a
 *      plausible logo in the right slot is never questioned again. Two assertions below close that:
 *      one on unrendered files, one on the star path itself.
 *
 *   3. AN OUTBOUND LINK THAT LOSES `target`/`rel` LOSES IT QUIETLY. These six anchors are the only
 *      links on this page that leave the application at all — before this wave the landing page had
 *      no external `href` of any kind — so there is no established habit here for an editor to copy,
 *      and a link that opens in place still WORKS. It just takes a visitor out of a page they were
 *      about to sign in to.
 *
 *   4. A `mask-*` PROPERTY THAT LOSES ITS `-webkit-` TWIN BREAKS ONE ENGINE ONLY. The unprefixed
 *      form is what a reviewer reads; the prefixed one is what several shipping browsers actually
 *      apply. Drop `WebkitMaskImage` and the seal's `<span>` stops being a mark at all: with no
 *      mask in force, its `backgroundColor` paints the whole box and the corner becomes a solid
 *      white rectangle — on those browsers, and nowhere else. This is the ONE case that produces
 *      that rectangle, and failure 1 records why a missing FILE does not: an absent mask property
 *      paints everything, an unresolved mask source paints nothing.
 *
 * ── WHY THIS READS SOURCE RATHER THAN LOADING THE PAGE ──────────────────────────────────────────
 *
 * There is no React renderer in this repository's devDependencies — `discarded-work-unit.spec.ts`,
 * `dashboard-tile-parity-unit.spec.ts` and `media-card-layout-unit.spec.ts` all say so and all read
 * their subjects the same way. A browser spec would also be the wrong instrument for failure 1 in
 * particular: Playwright does not fail a page because an `<img>` 404'd, and asserting on a rendered
 * mark's box would pass on the white rectangle. What has to be checked is the one pair of facts a
 * running page cannot state — that the path in the source and the file on the disk are the same
 * path — and that is a filesystem question rather than a rendering one.
 *
 * This is the automated form of a step that was being done by hand ("confirm on disk that every
 * asset path referenced by the hero exists"). A manual step is done once, by the person who already
 * knows the answer.
 */

const ROOT = join(__dirname, "..");
const PUBLIC_DIR = join(ROOT, "public");
const LOGOS_DIR = join(PUBLIC_DIR, "logos");

const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

/**
 * The same source with its comments taken out — the helper `media-card-layout-unit.spec.ts` and
 * `overlay-layering-unit.spec.ts` both use, for the reason they both give: the house style is long
 * prose naming the defect a rule closed, so `HeroLanding.tsx` contains the sentence
 * "`public/logos/centre-of-excellence.svg` was written while this band was being built" in a comment
 * — the exact string an assertion about that file would otherwise match. Every count and every path
 * scan below runs through this.
 */
const codeOnly = (relative: string) =>
  read(relative)
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/(^|[^:])\/\/.*$/gm, "$1");

const LANDING = "components/hero/HeroLanding.tsx";
const LOGO_COMPONENT = "components/WorkshopLogo.tsx";

/** Every file the landing page is assembled from: the server component and the whole hero island. */
const PAGE_SOURCES = [
  "app/page.tsx",
  ...readdirSync(join(ROOT, "components/hero"))
    .filter((name) => name.endsWith(".ts") || name.endsWith(".tsx"))
    .map((name) => "components/hero/" + name)
];

/**
 * A root-relative asset path in a string literal — the only shape a `public/` file can be referenced
 * by from this page. Deliberately wider than the two extensions in use today: the point is to catch
 * the NEXT asset somebody adds, and a check that only knows about `.svg` and `.png` stops being a
 * check the first time a font or a `.webp` arrives.
 */
const ASSET_REFERENCE =
  /["'`](\/[A-Za-z0-9_\-./]+\.(?:svg|png|jpe?g|webp|gif|avif|ico|woff2?|json|txt|csv))["'`]/g;

const referencedAssets = (): string[] => {
  const found = new Set<string>();
  for (const source of PAGE_SOURCES) {
    for (const match of codeOnly(source).matchAll(ASSET_REFERENCE)) found.add(match[1]);
  }
  return [...found].sort();
};

test("the comment stripper really removed the prose these assertions would otherwise match", () => {
  // Without this the file is vacuously green in both directions. `HeroLanding.tsx` names the deleted
  // orphan, quotes the star path, and discusses `<img>`, `target="_blank"` and every mask property
  // in prose — so a negative assertion would fail for an honest-looking wrong reason, and every
  // count below would be counting sentences.
  expect(read(LANDING)).toContain("centre-of-excellence.svg");
  expect(codeOnly(LANDING)).not.toContain("centre-of-excellence.svg");
});

test("every asset path the landing page references exists on disk", () => {
  const assets = referencedAssets();

  // The scan finding nothing would make the loop below pass while proving nothing, and that is the
  // state this page was in for its whole life until requirement 15 — no file-based asset anywhere.
  expect(assets.length).toBeGreaterThan(0);

  for (const asset of assets) {
    const onDisk = existsSync(join(PUBLIC_DIR, asset.replace(/^\//, "")));
    expect(onDisk, asset + " is referenced by the landing page but is not in public/").toBe(true);
  }
});

test("public/logos holds nothing the landing page does not render", () => {
  // THE ORPHAN GATE. A file here that no source references is either a mark that was meant to be
  // wired up and was forgotten, or one that was deliberately not wired up — and from the folder
  // alone those two are indistinguishable, which is exactly how a stray fourth copy of the Centre's
  // mark came to sit here referenced by nothing.
  //
  // ⚠ THE REASON WRITTEN HERE UNTIL 2026-08-30 WAS WRONG AND IS CORRECTED RATHER THAN DELETED,
  // because it was load-bearing in an argument that refused to draw the Centre's logo at all. It
  // said the orphan was "a mislabelled copy of this product's own logo… waiting to be mistaken for
  // the Centre of Excellence's". It was not mislabelled: this application is a Centre of Excellence
  // project and wears the Centre's mark, so the file and `app/icon.svg` were identical because they
  // are the SAME MARK. The file still had to go, for the reason below rather than that one.
  const referenced = new Set(referencedAssets().filter((asset) => asset.startsWith("/logos/")));
  const onDisk = readdirSync(LOGOS_DIR).map((name) => "/logos/" + name);

  expect(onDisk.length).toBeGreaterThan(0);
  for (const file of onDisk) {
    expect(referenced.has(file), file + " is in public/logos but nothing on the landing page renders it").toBe(true);
  }
});

test("no file in public/logos duplicates the star path that WorkshopLogo already declares", () => {
  // WHAT THIS REFUSES IS A DUPLICATED FILE, NOT THE MARK. `WorkshopLogo.tsx:7-10` warns that the
  // path data is a hand-transcription and already lives in three places that must be edited in
  // lockstep — the Android drawable, that component, and the favicon. A copy under `public/logos/`
  // would be a fourth, and the way that fails is silent: somebody redraws the star in three places,
  // the fourth keeps serving the old one, and the landing page quietly stops matching the app.
  //
  // The landing page DOES render this mark, beside the Centre of Excellence's name in the colophon
  // — it draws `<WorkshopLogo>`, which is a call site of the one declaration and not a copy of it,
  // which is why that render does not trip this test and must not be "fixed" into a file.
  //
  // (This test was named "…under an institution's name" and justified as protecting against a
  // misattribution. That premise was wrong — see the orphan gate above — and the rename is the
  // correction. The assertion itself is unchanged and still wanted.)
  //
  // Derived from the component rather than typed out here, so a redraw of the mark keeps this
  // honest instead of pinning it to a path that no longer exists.
  const star = codeOnly(LOGO_COMPONENT).match(/d="([^"]+)"/);
  expect(star, "WorkshopLogo no longer declares a single path — re-derive this assertion").not.toBeNull();
  const path = star ? star[1] : "";

  for (const name of readdirSync(LOGOS_DIR)) {
    // latin1 rather than utf8: two of these files are binary, and a lossy decode could in principle
    // manufacture or destroy a match. Nothing here needs the bytes to mean anything.
    const bytes = readFileSync(join(LOGOS_DIR, name)).toString("latin1");
    expect(bytes.includes(path), name + " contains WorkshopLogo's own path data").toBe(false);
  }
});

test("the three institutional destinations are the ones on record, each written exactly once", () => {
  const code = codeOnly(LANDING);

  // Requirement 16's three links, and requirement 17's decision that the Centre of Excellence is a
  // destination rather than an import. Once each because each is declared in a constant and every
  // surface renders it from there — a second literal is the beginning of two hosts that disagree,
  // which is the whole reason IIT_KHARAGPUR and DC_HANDICRAFTS were hoisted to module scope when
  // the marks landed on a second surface.
  const destinations = ["https://www.iitkgp.ac.in/", "https://handicrafts.nic.in/", "https://cxa-cms.vercel.app/"];
  for (const href of destinations) {
    expect(code.split(href).length - 1, href + " should appear exactly once").toBe(1);
  }
});

test("every outbound anchor opens in a new tab, says so, and carries rel=noreferrer", () => {
  const code = codeOnly(LANDING);
  // JSX attribute values here never contain a `>`, so the open tag really does end at the first one.
  const anchors = code.match(/<a\s[\s\S]*?>/g) ?? [];

  // TEN ANCHORS: six in-page fragments in the footer nav, four that leave the application.
  //
  // It was twelve until 2026-08-30, and the two that went were the boundary credit's — the DataMeet
  // source link and the CC BY licence link, removed together with the notice they belonged to. That
  // removal is not a tidy-up and the reasoning is not in this file: `indiaOutline.ts` records that
  // the dataset is CC-0 by its own README, so the blanket CC BY clause in its repository README
  // ("anything not explicitly licensed") never reached this file and there was no obligation to
  // discharge. `HeroLanding.tsx`'s footer carries the same note where the notice used to render.
  //
  // ⚠ IF THESE NUMBERS EVER GO BACK UP BY TWO, CHECK WHY BEFORE ACCEPTING IT. A restored credit
  // means somebody swapped the geometry for a source that DOES require attribution — which is the
  // correct thing to do in that case, and is exactly the change both files ask to be re-decided.
  expect(anchors.length).toBe(10);

  const outbound = anchors.filter((tag) => !/href="#/.test(tag));
  expect(outbound.length).toBe(4);

  for (const tag of outbound) {
    const href = tag.match(/href=(\{[^}]*\}|"[^"]*")/);
    const named = href ? href[1] : tag;
    expect(/target="_blank"/.test(tag), named + " leaves the app without target=\"_blank\"").toBe(true);
    // `noreferrer` implies `noopener` in every browser this app supports, and it is the house form
    // — roughly eleven call sites across the tree against one each of the alternatives.
    expect(/rel="noreferrer"/.test(tag), named + " leaves the app without rel=\"noreferrer\"").toBe(true);
  }

  // One announcement per outbound anchor, no more and no fewer. A tab that opens unannounced is
  // WCAG 3.2.5; two announcements on one link is a screen reader saying it twice.
  expect(code.split("(opens in a new tab)").length - 1).toBe(outbound.length);
});

test("both institutional images stay decorative, because the name is on the anchor", () => {
  const code = codeOnly(LANDING);
  const images = code.match(/<img\b/g) ?? [];

  expect(images.length).toBe(2);
  // Every one of them, not merely one: `alt=""` is correct here ONLY because each anchor carries the
  // institution's full name — visible text in the colophon, `sr-only` text in the masthead. An
  // `alt="Indian Institute of Technology Kharagpur"` added "for accessibility" would make a screen
  // reader announce the institution twice on the band, which is the failure that reads as a fix.
  expect(code.split('alt=""').length - 1).toBe(images.length);
  expect(code).not.toMatch(/alt="[^"]+"/);
});

test("every masked property on the landing page ships its -webkit- twin", () => {
  const code = codeOnly(LANDING);
  // Two call sites: the seal painted through its own alpha, and the closing band's radial fade.
  const properties = [...code.matchAll(/\bmask([A-Z][A-Za-z]*)\s*:/g)].map((match) => match[1]);
  expect(properties.length).toBeGreaterThan(0);

  for (const property of new Set(properties)) {
    // The two spellings never overlap as substrings — `WebkitMaskImage` capitalises the M, so
    // `maskImage` is not inside it — which is what lets these be two independent counts rather than
    // one count minus the other.
    const plain = code.split("mask" + property + ":").length - 1;
    const webkit = code.split("WebkitMask" + property + ":").length - 1;
    expect(webkit, "mask" + property + " is declared " + plain + "x and WebkitMask" + property + " " + webkit + "x").toBe(
      plain
    );
  }
});

test("the masthead marks and the wrappers that hide them are gated at md together", () => {
  const code = codeOnly(LANDING);

  // THE "NOTHING MOVES ON A PHONE" GUARANTEE, WHICH IS TWO CLASSES AND NOT ONE. `hidden … md:flex`
  // on the anchors takes the marks out. `contents md:flex` on the two groups that hold them is what
  // stops the WRAPPERS changing the row: a `display: contents` box is not in the box tree at all, so
  // below md the header's flex items are once again exactly the wordmark cluster and the button.
  // Wrapping the marks in ordinary flex groups instead measured 5.1px wider on the wordmark and
  // 5.9px narrower on the button at 360px — with the marks already hidden. Two groups, two marks,
  // and the counts must stay level: gating one and not the other is the shape of the regression.
  expect(code.split("contents md:flex").length - 1).toBe(2);
  expect(code.split("hidden shrink-0 rounded-md transition").length - 1).toBe(2);
  // `sm:` here would put the marks back into the band of widths where `data-larger-text` overflows
  // the row — 640px and 641px and nowhere else, which is why it was measured rather than reasoned.
  expect(code).not.toContain("contents sm:flex");
});
