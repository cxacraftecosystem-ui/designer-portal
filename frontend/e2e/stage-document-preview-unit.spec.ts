import { expect, test } from "@playwright/test";

import { sliceStageBlocks } from "@/components/designworkshop/report/StageDocumentPreview";
import type { PreviewBlock } from "@/components/designworkshop/report/previewModel";
import type { DwRun } from "@/lib/designWorkshops";

/**
 * FINDING ONE STAGE INSIDE A WHOLE DOCUMENT, PINNED.
 *
 * WHY THIS SPEC EXISTS. `GET /design-workshops/{id}/report/preview` returns the ENTIRE document and
 * its blocks carry no stage identity — `HeadingBlock.bookmark` exists on the dataclass and the
 * builder never assigns it. So `StageDocumentPreview` finds the stage by the only thing genuinely
 * on the wire: the heading whose text is this stage's title, down to the next heading at the same
 * level or above. That locator is the one piece of judgement on a screen whose whole promise is
 * that it is NOT a fifth renderer of the report, and every way it can be wrong is silent:
 *
 *   • a heading it cannot find, reported as a stage that prints nothing — rule 10, absence reading
 *     as "there is nothing here", the most repeated bug class in this repository;
 *   • a slice that runs past its own stage into the next one, so a designer proofreads somebody
 *     else's prose believing it is theirs;
 *   • a slice that stops at the stage's own SUB-heading, so the paragraphs and galleries under it
 *     are invisible on the one screen built to show them.
 *
 * None of that is visible in a screenshot: the panel draws a plausible document either way. It is a
 * question about which sub-array a function returns, which is what a unit spec can hold still.
 *
 * WHAT IT PINS, AND HOW. BEHAVIOUR ONLY — the blocks a caller receives, never the source text of
 * the module. `review-ranking-unit.spec.ts` failed on 2026-08-25 for exactly that reason when the
 * drag mechanics moved between files while keeping every guarantee, and the lesson is written into
 * its own header. So nothing below asserts a regex, a helper name or a line of `fold`.
 *
 * WHAT IT DOES NOT COVER. The fetch, the `drawn` ref and the disclosure (React state, and there is
 * no React renderer in this repository's devDependencies), and the blocks' own rendering
 * (`ReportBlock`, which the report page's specs cover).
 *
 * PURE NODE — no browser, no server, no IndexedDB.
 * Run: `npx playwright test e2e/stage-document-preview-unit.spec.ts --reporter=line`
 */

/** A run carrying nothing but its words — every mark the slice never reads. */
function run(text: string): DwRun {
  return { text, bold: false, italic: false, script: "LATIN", color: null };
}

/**
 * One heading.
 *
 * `number` is a SEPARATE FIELD on `HeadingBlock`, and some templates print the section number there
 * while others fold it into the runs — which is the whole reason one of the tests below exists.
 */
function heading(text: string, level: number, number = ""): PreviewBlock {
  return { type: "HEADING", level, number, bookmark: "", runs: [run(text)] };
}

function para(text: string): PreviewBlock {
  return { type: "PARAGRAPH", style: "Body", align: "left", runs: [run(text)] };
}

/** The words a slice puts on the page, so a test can name content instead of counting indices. */
function words(blocks: PreviewBlock[] | null): string[] {
  return (blocks ?? []).flatMap((block) => {
    if (block.type === "HEADING" || block.type === "PARAGRAPH") {
      return [block.runs.map((one) => one.text).join("")];
    }
    return [];
  });
}

const STAGE = "Cluster, Area & Craft Background";

test("a matching heading yields that heading and its stage's blocks, and stops at the next stage", () => {
  /*
    THE FAILURE THIS PINS. The slice running to the end of the document, so a panel headed "how this
    stage prints in the report" shows this stage AND every stage after it. A designer proofreading
    the cluster background would be reading the market survey underneath it as their own text, and
    the count printed above it ("Showing the 6 blocks this stage contributes") would assert it in a
    number.
  */
  const doc: PreviewBlock[] = [
    heading("Part A — The Cluster", 1),
    heading(STAGE, 2),
    para("Bagru's hand-block printing sits on the Sanganer road."),
    para("Nine households still boil the dabu."),
    heading("Market Survey", 2),
    para("Belongs to stage 8, not to this one.")
  ];

  const slice = sliceStageBlocks(doc, STAGE);

  expect(words(slice), "the slice starts at the stage's own heading and ends before the next stage").toEqual([
    STAGE,
    "Bagru's hand-block printing sits on the Sanganer road.",
    "Nine households still boil the dabu."
  ]);
  expect(words(slice), "the next stage's prose must never print as this stage's contribution").not.toContain(
    "Belongs to stage 8, not to this one."
  );
});

test("a heading that is not in the document returns null, and null is not the same answer as []", () => {
  /*
    THE DISTINCTION THE CALLER'S HONESTY RESTS ON. `[]` means "this stage prints nothing", which is a
    legitimate answer the panel states plainly. `null` means "I could not find where this stage
    begins", which makes the panel fall back to the whole document AND say in amber that it has done
    so. Collapsing the two — a `?? []`, an early `return []` on the not-found branch — is the
    silent-emptiness bug this repository keeps paying for: a stage whose heading a template words
    differently would render as a stage that contributes nothing, and a designer would go looking in
    the database for four paragraphs they had already saved.

    Asserted as `toBeNull`, and then asserted AGAIN as not the empty array, because `toBeFalsy` and a
    bare truthiness test pass for both and would let the collapse through.
  */
  const doc: PreviewBlock[] = [heading("Part A — The Cluster", 1), para("No stage heading anywhere here.")];

  expect(sliceStageBlocks(doc, STAGE), "a heading nobody printed is not found").toBeNull();
  expect(sliceStageBlocks(doc, STAGE), "and 'not found' is not 'contributes nothing'").not.toEqual([]);

  // The same rule at the two edges: an empty document, and a stage with no title to match on.
  expect(sliceStageBlocks([], STAGE), "an empty document has no stage heading either").toBeNull();
  expect(sliceStageBlocks(doc, "   "), "a blank title matches nothing rather than everything").toBeNull();
});

test("a SHALLOWER heading ends the slice, not only an equal one", () => {
  /*
    THE FAILURE THIS PINS. A stage that is the LAST one under a part heading is followed by the next
    PART, whose level is smaller — 1 against the stage's 2. A test for equality alone finds no
    level-2 heading after it and runs the slice to the end of the document, which on the last stage
    of a part means every remaining stage plus the annexures and the signature page.
  */
  const doc: PreviewBlock[] = [
    heading("Design Brief & Direction", 2),
    para("The direction agreed with the cluster."),
    heading("Part B — Annexures", 1),
    para("Belongs to the next part.")
  ];

  expect(
    words(sliceStageBlocks(doc, "Design Brief & Direction")),
    "the next PART closes the slice exactly as the next stage would"
  ).toEqual(["Design Brief & Direction", "The direction agreed with the cluster."]);
});

test("a DEEPER heading stays inside the slice — a stage's own sub-headings are its own", () => {
  /*
    THE OTHER HALF OF THE SAME RULE, AND THE ONE THAT EMPTIES THE SCREEN. Stage 4 prints sub-headings
    of its own: the traditional motifs, then the contemporary ones, each with bullets and a gallery
    under it. If any following heading ended the slice, the panel would show the stage heading and
    nothing else — and the sentence above it would read "Showing the 1 block this stage contributes"
    about a stage holding two galleries and four paragraphs.
  */
  const doc: PreviewBlock[] = [
    heading(STAGE, 2),
    para("The cluster, in a paragraph."),
    heading("Traditional motif photographs", 3),
    para("Twelve plates, collected at the workshop."),
    heading("Contemporary motifs, forms and colours", 3),
    para("What the market is asking for now."),
    heading("Market Survey", 2),
    para("The next stage.")
  ];

  expect(words(sliceStageBlocks(doc, STAGE)), "sub-headings and everything under them belong to the stage").toEqual([
    STAGE,
    "The cluster, in a paragraph.",
    "Traditional motif photographs",
    "Twelve plates, collected at the workshop.",
    "Contemporary motifs, forms and colours",
    "What the market is asking for now."
  ]);
});

test("the section number matches whether a template prints it beside the runs or inside them", () => {
  /*
    THE FAILURE THIS PINS. The builder puts "4." in `HeadingBlock.number` on some templates and
    inside the heading's runs on others. A comparison that kept the number matched on one template
    and fell through to the whole-document fallback on the next — so the same workshop, previewed
    from the same stage, showed one stage under one template and forty pages under another, with an
    amber notice blaming the wording of a heading that was perfectly correct.

    Both shapes are asserted to produce the SAME slice, which is the property that matters: the
    stage title in the registry never carries a number, and a designer must not have to know which
    template they are on.
  */
  const beside: PreviewBlock[] = [
    heading(STAGE, 2, "4."),
    para("The stage's one paragraph."),
    heading("Market Survey", 2, "8.")
  ];
  const inside: PreviewBlock[] = [
    heading(`4. ${STAGE}`, 2),
    para("The stage's one paragraph."),
    heading("8. Market Survey", 2)
  ];

  expect(words(sliceStageBlocks(beside, STAGE)), "a number in its own field must not defeat the match").toEqual([
    STAGE,
    "The stage's one paragraph."
  ]);
  expect(
    sliceStageBlocks(inside, STAGE),
    "nor must a number folded into the runs — that is the whole-document fallback, wrongly taken"
  ).not.toBeNull();
  expect(
    words(sliceStageBlocks(inside, STAGE)).slice(1),
    "and the numbered template must slice to the same content as the unnumbered one"
  ).toEqual(["The stage's one paragraph."]);
});

test("case and doubled spaces do not decide whether a stage is found", () => {
  /*
    A TITLE ASSEMBLED FROM TWO PIECES WITH AN EMPTY ONE BETWEEN THEM CARRIES A DOUBLE SPACE, and the
    heading a reader sees collapses it — the reason `selectFilter.fold` collapses runs of whitespace
    on BOTH sides of every comparison in this app. Matching what the reader visibly sees is the whole
    job of this locator; failing on an invisible second space would send the panel to its amber
    fallback and blame a template that had done nothing wrong.
  */
  const doc: PreviewBlock[] = [heading("cluster, area &  craft   BACKGROUND", 2), para("Found anyway.")];

  expect(words(sliceStageBlocks(doc, ` ${STAGE} `)), "one invisible space is not a different stage").toEqual([
    "cluster, area &  craft   BACKGROUND",
    "Found anyway."
  ]);
});
