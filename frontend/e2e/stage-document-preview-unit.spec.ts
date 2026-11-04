import { expect, test } from "@playwright/test";

import {
  PreviewDeadlineError,
  sliceStageBlocks,
  withDeadline
} from "@/components/designworkshop/report/StageDocumentPreview";
import type { PreviewBlock } from "@/components/designworkshop/report/previewModel";
import type { DwRun } from "@/lib/designWorkshops";

function run(text: string): DwRun {
  return { text, bold: false, italic: false, script: "LATIN", color: null };
}

function heading(text: string, level: number, number = ""): PreviewBlock {
  return { type: "HEADING", level, number, bookmark: "", runs: [run(text)] };
}

function para(text: string): PreviewBlock {
  return { type: "PARAGRAPH", style: "Body", align: "left", runs: [run(text)] };
}

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
  const doc: PreviewBlock[] = [heading("Part A — The Cluster", 1), para("No stage heading anywhere here.")];

  expect(sliceStageBlocks(doc, STAGE), "a heading nobody printed is not found").toBeNull();
  expect(sliceStageBlocks(doc, STAGE), "and 'not found' is not 'contributes nothing'").not.toEqual([]);

  expect(sliceStageBlocks([], STAGE), "an empty document has no stage heading either").toBeNull();
  expect(sliceStageBlocks(doc, "   "), "a blank title matches nothing rather than everything").toBeNull();
});

test("a SHALLOWER heading ends the slice, not only an equal one", () => {
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
  const doc: PreviewBlock[] = [heading("cluster, area &  craft   BACKGROUND", 2), para("Found anyway.")];

  expect(words(sliceStageBlocks(doc, ` ${STAGE} `)), "one invisible space is not a different stage").toEqual([
    "cluster, area &  craft   BACKGROUND",
    "Found anyway."
  ]);
});

test("a load that never answers fails at its deadline instead of building for ever", async () => {
  const never = new Promise<string>(() => undefined);
  await expect(withDeadline(never, 20)).rejects.toBeInstanceOf(PreviewDeadlineError);
});

test("a load that answers in time is passed through, value and failure alike", async () => {
  await expect(withDeadline(Promise.resolve("pages"), 1000)).resolves.toBe("pages");
  await expect(withDeadline(Promise.reject(new Error("401")), 1000)).rejects.toThrow("401");
});
