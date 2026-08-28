import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * THE FIFTH SURFACE THAT PRINTS A PAGE LABEL, PINNED FROM THE SIDE THAT OWNS IT.
 *
 * Four renderers write a report FILE — `report_pdf.py`, `report_docx.py`, and their Kotlin twins
 * `PdfWriter.kt` and `DocxWriter.kt` — and all four print "Page N of M" in the running foot; the
 * two .docx writers resolve M from Word's `NUMPAGES` field, the two PDF writers take it from their
 * measuring pass. `ReportSheet.tsx` is the fifth, it is the only one that is a SCREEN, and it was
 * the last one the fix reached: it printed "Page 3" while the document downloaded from the same
 * page said "Page 3 of 12", so a designer proofing the report and the officer receiving it were
 * reading two differently-shaped numbers for one page.
 *
 * `backend/tests/test_report_parity.py` asserts the same substring, reading this file from the
 * other side of the repository — deliberately, because the class of defect being pinned is a fix
 * landing on one surface and not the others, and a pin that lives only next to the code it guards
 * is removed in the same edit that breaks it. WHAT IS ADDED HERE AND ONLY HERE is the second half
 * of the change: what the M this preview prints is WORTH.
 *
 * AND THAT ANSWER CHANGED, WHICH IS WHY THE THIRD TEST BELOW READS AS IT DOES. It used to be a
 * FLOOR: `splitIntoSheets` cut the block stream only where the template declared a break, so the
 * count could only ever be too low. `reportPagination` measures the blocks now and the sheets are
 * real pages — so the M is a close estimate of the file's own count, arrived at with THIS
 * browser's fonts while the .pdf is laid out by ReportLab in whichever face it resolved and the
 * .docx is paginated by Word on open. A number that looks authoritative is the one that gets
 * quoted, so real pagination RAISES the cost of overclaiming rather than removing it and the
 * sentence beside the number had to get more precise, not quieter. Print the total and delete the
 * sentence and the preview asserts a page count it cannot stand behind — which is the exact
 * failure a total read off a preview and into a covering email produces.
 *
 * AND THE COVERING EMAIL COMES FROM `Ctrl+P`, which is why the last test here is about print. The
 * qualifying sentence lives in a strip marked `data-rp-noprint` and the print block drops it with
 * the rest of the chrome, so a printed sheet would carry the total with nothing left to qualify it
 * — in the one artefact this screen's own header says a designer with no backend reachable
 * actually hands over. So the "of M" is its own element and `@media print` hides it. That is a rule
 * in a template literal with no runtime behaviour of its own: nothing but a source read notices
 * when somebody tidies it away.
 *
 * Source reads, for the reason the sibling unit specs give: there is no React renderer in this
 * repository's devDependencies, so a component cannot be mounted here.
 */

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const SHEET = "components/designworkshop/report/ReportSheet.tsx";
const MODEL = "components/designworkshop/report/previewModel.ts";

test("the preview's running foot prints the total, the way all four file renderers do", () => {
  const sheet = read(SHEET);
  // Two elements, not one: the "of M" is wrapped so print can drop it, so the ordinal and the
  // total are pinned separately. Read together they are still the one label the foot renders.
  expect(sheet).toContain("Page {sheet.pageNumber}");
  expect(sheet).toContain('<span className="rp-of"> of {sheets.length}</span>');
});

test("the sheet's accessible name carries the same two numbers the foot prints", () => {
  // The foot is `aria-hidden` — it is furniture, and a screen reader that read it on every page
  // would read the header and footer text twice. So the page number reaches assistive technology
  // only through the article's label, and a label saying "Page 3" beside a foot saying
  // "Page 3 of 12" is the same divergence one level down.
  expect(read(SHEET)).toContain("`Page ${sheet.pageNumber} of ${sheets.length}`");
});

test("the total is qualified on screen as what it now is: an estimate, not the file's own count", () => {
  const sheet = read(SHEET);
  // ── THIS ASSERTION USED TO PIN THE WORD "floor", AND THE WORD CHANGED BECAUSE THE CODE DID ──
  //
  // Until `reportPagination` landed, the preview cut the block stream only where the template
  // DECLARED a break, so the count could only ever be too low and "a floor, not the final count"
  // was exactly right. The blocks are measured now and the pages are real pages — so the count is
  // no longer a floor, and it is not the file's own count either: this browser measures with the
  // fonts it has, the .pdf is laid out by ReportLab in whichever face it resolved, and the .docx
  // is paginated by Word on open. Three engines that disagree about one line can disagree about
  // one page.
  //
  // MAKING THE PREVIEW TRUSTWORTHY RAISES THE COST OF OVERCLAIMING RATHER THAN REMOVING IT, which
  // is why this test was corrected rather than deleted: a number that now looks authoritative is
  // the one most likely to be quoted, so the sentence beside it has to be MORE precise than the
  // one it replaced, not less.
  expect(sheet).toContain("measured in this browser");
  expect(sheet).toContain("close estimate of the file");
  // Named rather than merely present: the sentence has to be about the number in the foot.
  expect(sheet).toContain("in each running foot below");
  // And the old claim must not survive beside the new one: two qualifications of one number, one
  // of them describing a mechanism the file no longer has, is worse than either alone.
  expect(sheet).not.toContain("is a floor, not the");
});

test("the total does not survive into a printed file, because nothing there can qualify it", () => {
  const sheet = read(SHEET);
  // The strip that calls the total a floor is chrome and is dropped in print...
  expect(sheet).toMatch(/\[data-rp-noprint\][^}]*display: none/);
  // ...so the total goes with it, leaving the ordinal the browser's own pagination still supports.
  expect(sheet).toMatch(/@media print[\s\S]*\.rp-of \{ display: none !important; \}/);
  // And it is only reachable as its own element, which is the whole point of the wrapper.
  expect(sheet).toContain('<span className="rp-of">');
});

test("previewModel documents the label every renderer actually draws", () => {
  const model = read(MODEL);
  // The .pdf writer has not drawn a bare "Page N" since the parity fix; a comment that says it
  // does is how the next reader concludes the preview is already in agreement.
  expect(model).not.toContain('"Page N"');
  expect(model).toContain("Page N of M");
});
