/**
 * The one background scroll lock, refcounted, shared by every surface that covers the page.
 *
 * WHY THIS IS A MODULE AND NOT THREE EFFECTS. Three surfaces freeze the page behind them — the
 * navigation sheet, `FieldDialog`, and the rich-text editor's full-screen mode — and until this
 * file existed each one locked scrolling its own way. Two of them wrote the `nav-scroll-locked`
 * class onto <html> directly, which is safe only while exactly one of them is ever open: a dialog
 * mounted globally (the app-update prompt, an unsaved-changes confirm) closing while a full-screen
 * editor was still up STRIPPED THE CLASS OUT FROM UNDER IT, and the document behind the editor
 * started panning again mid-sentence. The third locked `overflow` on <body> alone, which is the
 * case this project's own stylesheet documents iOS Safari as ignoring — so on an iPhone every
 * confirm, every delete prompt and every unsaved-changes dialog in the app sat over a page that
 * carried on scrolling under the reader's thumb, while the component's header promised the
 * opposite. It also paid nothing back for the scrollbar it removed, so every centred dialog on a
 * desktop jumped sideways by half the scrollbar's width as it opened.
 *
 * A refcount is the only thing that makes nesting correct. The lock goes on when the first owner
 * claims it and comes off when the LAST one lets go, so the order they close in stops mattering.
 *
 * WHAT THE LOCK IS, and why it is not `document.body.style.overflow`:
 *
 * - **It lives on <html>.** iOS Safari quietly ignores `overflow: hidden` on the body and keeps
 *   panning the page under the overlay. The rules in `globals.css` are keyed on
 *   `html.nav-scroll-locked` for exactly that reason.
 * - **The scrollbar's width is paid back.** Freezing the document takes the desktop scrollbar with
 *   it; without `--nav-scroll-gutter` as body padding, everything centred on the page — a dialog
 *   panel included — shifts as the lock lands. `position: fixed` chrome cannot inherit body
 *   padding, so `.nav-island-frame`, `.nav-sheet-overlay` and the full-screen editor re-pay the
 *   same variable themselves.
 * - **The scroll POSITION survives.** Every engine we target keeps the offset across an
 *   `overflow: hidden` spell, but one that clamped it to zero would dump a reader who opened a
 *   dialog halfway down a long record back at the top. The guarantee is nearly free.
 *
 * The class name is `nav-scroll-locked` for history — the navigation sheet needed it first — and is
 * deliberately NOT renamed here: `nav-sheet-scroll.spec.ts` and the three `padding-right` rules that
 * re-pay the gutter all name it, and a rename that missed one of them would be silent.
 */

/** How many surfaces currently hold the lock. The last one out puts the page back. */
let holders = 0;
/** Where the document was scrolled to when the FIRST holder claimed the lock. */
let restoreTo = 0;

/** Claim the lock. Safe to call from a surface that is already inside another locked one. */
export function lockPageScroll(): void {
  if (typeof document === "undefined") return;
  holders += 1;
  if (holders > 1) return;
  const root = document.documentElement;
  // Measured BEFORE the class lands: once the document is frozen the scrollbar is already gone and
  // this difference reads zero.
  const gutter = window.innerWidth - root.clientWidth;
  restoreTo = window.scrollY;
  root.style.setProperty("--nav-scroll-gutter", `${gutter}px`);
  root.classList.add("nav-scroll-locked");
}

/** Release the lock. The page is only unfrozen once every holder has released. */
export function unlockPageScroll(): void {
  if (typeof document === "undefined") return;
  holders = Math.max(0, holders - 1);
  if (holders > 0) return;
  const root = document.documentElement;
  root.classList.remove("nav-scroll-locked");
  root.style.removeProperty("--nav-scroll-gutter");
  if (window.scrollY !== restoreTo) window.scrollTo(0, restoreTo);
}
