import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { MEASUREMENT_GRID_PURPOSE } from "@/components/media/GridMeasurement";
import {
  MEDIA_NOTE_GRID_PURPOSE,
  composePointerNote,
  composeRecordCount,
  countableMediaRows,
  hasPointerNote,
  mediaNoteBreakdown,
  mediaNoteOverrun,
  stripPointerNote,
  type MediaNoteRow
} from "@/components/designworkshop/mediaNoteGrammar";
import { recordMediaNoteRole } from "@/components/designworkshop/stageFieldRoles";
import type { DwEntity, DwField, DwFieldType } from "@/lib/designWorkshops";

const COMPONENT = () =>
  readFileSync(join(__dirname, "..", "components", "designworkshop", "StageMediaNoteField.tsx"), "utf8");

function row(mediaType: string, extra: Partial<MediaNoteRow> = {}): MediaNoteRow {
  return { mediaType, originalFilename: `${mediaType.toLowerCase()}.bin`, ...extra };
}

test("the composed count is byte-identical to the sentence the server writes", () => {
  expect(composeRecordCount({ subject: "artisan", rows: [row("IMAGE"), row("AUDIO")] })).toBe(
    "Attached to the artisan record: 1 photograph, 1 audio note."
  );
});

test("the word order is the sentence's order and never the data's", () => {
  const shuffled = [row("OTHER"), row("PDF"), row("AUDIO"), row("VIDEO"), row("IMAGE")];
  expect(mediaNoteBreakdown(shuffled)).toBe("1 photograph, 1 video, 1 audio note, 1 document, 1 file");
});

test("PDF and DOCUMENT are one word, and a token this build has never heard of is still counted", () => {
  expect(mediaNoteBreakdown([row("PDF"), row("DOCUMENT")])).toBe("2 documents");
  expect(mediaNoteBreakdown([row("HOLOGRAM"), row("OTHER")])).toBe("2 files");
  expect(mediaNoteBreakdown([{ mediaType: null }])).toBe("1 file");
});

test("nothing countable is null and never a sentence saying zero", () => {
  expect(composeRecordCount({ subject: "artisan", rows: [] })).toBeNull();
  expect(composePointerNote({ subject: "artisan", base: "anything", rows: [] })).toBeNull();
});

test("a measurement-grid frame is neither counted nor offered, under the one shared spelling", () => {
  expect(MEDIA_NOTE_GRID_PURPOSE).toBe(MEASUREMENT_GRID_PURPOSE);

  const grid = row("IMAGE", { extraMetadata: { purpose: MEASUREMENT_GRID_PURPOSE } });
  const rows = [row("IMAGE"), grid];
  expect(mediaNoteBreakdown(rows)).toBe("1 photograph");
  expect(countableMediaRows(rows)).toHaveLength(1);
  expect(countableMediaRows([row("IMAGE", { extraMetadata: "MEASUREMENT_GRID" })])).toHaveLength(1);
  expect(countableMediaRows([row("IMAGE", { extraMetadata: { purpose: "PORTRAIT" } })])).toHaveLength(1);
});

test("only a numbered prefix grows the making clause, and only the tool passes one", () => {
  const steps = [
    row("IMAGE", { originalFilename: "STAGE_STEP_1_a.jpg" }),
    row("IMAGE", { originalFilename: "STAGE_STEP_2_b.jpg" }),
    row("IMAGE", { originalFilename: "portrait.jpg" })
  ];
  expect(composeRecordCount({ subject: "tool", rows: steps, numberedPrefix: "STAGE_STEP_" })).toBe(
    "Attached to the tool record: 3 photographs, of which 2 document the making in order."
  );
  expect(composeRecordCount({ subject: "artisan", rows: steps })).toBe(
    "Attached to the artisan record: 3 photographs."
  );
  expect(
    composeRecordCount({ subject: "tool", rows: [steps[0]], numberedPrefix: "STAGE_STEP_" })
  ).toContain("of which 1 documents the making in order");
});

const FULL = "Attached to the artisan record: 4 photographs, 1 audio note.";

test("narrowing keeps the record's own count verbatim and adds a second sentence", () => {
  const narrowed = composePointerNote({ subject: "artisan", base: FULL, rows: [row("IMAGE")] });
  expect(narrowed).toBe(`${FULL} See in particular: 1 photograph.`);
  expect(narrowed?.startsWith(FULL)).toBe(true);
  expect(narrowed).not.toBe("Attached to the artisan record: 1 photograph.");
});

test("picking again replaces the clause instead of growing the sentence", () => {
  const once = composePointerNote({ subject: "artisan", base: FULL, rows: [row("IMAGE")] }) ?? "";
  const twice = composePointerNote({ subject: "artisan", base: once, rows: [row("IMAGE"), row("AUDIO")] }) ?? "";
  expect(twice).toBe(`${FULL} See in particular: 1 photograph, 1 audio note.`);
  expect(twice.match(/See in particular/g)).toHaveLength(1);
  expect(composePointerNote({ subject: "artisan", base: twice, rows: [row("IMAGE"), row("AUDIO")] })).toBe(twice);
});

test("clearing the selection leaves the record's own count and nothing else", () => {
  const narrowed = composePointerNote({ subject: "artisan", base: FULL, rows: [row("IMAGE")] }) ?? "";
  expect(stripPointerNote(narrowed)).toBe(FULL);
  expect(hasPointerNote(narrowed)).toBe(true);
  expect(hasPointerNote(FULL)).toBe(false);
});

test("a designer's own prose is not mistaken for a clause the control wrote", () => {
  const prose = "See in particular: the audio, though the video is longer. Ask the researcher.";
  expect(stripPointerNote(prose)).toBe(prose);
  expect(composePointerNote({ subject: "artisan", base: "", rows: [row("AUDIO")] })).toBe(
    "Of the media attached to the artisan record, see: 1 audio note."
  );
});

test("the sentence written over an empty base is itself strippable, so the control stays idempotent", () => {
  const first = composePointerNote({ subject: "artisan", base: "", rows: [row("IMAGE")] }) ?? "";
  expect(first).toBe("Of the media attached to the artisan record, see: 1 photograph.");
  expect(hasPointerNote(first), "the panel must be able to say the box already names files").toBe(true);
  expect(stripPointerNote(first), "there is no claim about the record to keep, so nothing is kept").toBe("");

  const second = composePointerNote({ subject: "artisan", base: first, rows: [row("IMAGE"), row("AUDIO")] });
  expect(second).toBe("Of the media attached to the artisan record, see: 1 photograph, 1 audio note.");
  expect(second?.match(/see:/gi)).toHaveLength(1);
  expect(second?.includes("See in particular")).toBe(false);

  expect(composePointerNote({ subject: "artisan", base: first, rows: [row("IMAGE")] })).toBe(first);
  expect(stripPointerNote(`${FULL} See in particular: 1 photograph.`)).toBe(FULL);
  expect(composePointerNote({ subject: "artisan", base: "Four files here", rows: [row("AUDIO")] })).toBe(
    "Four files here. See in particular: 1 audio note."
  );
});

test("the field's bound is measured, and an absent bound means unbounded rather than zero", () => {
  expect(mediaNoteOverrun("x".repeat(201), 200)).toBe(1);
  expect(mediaNoteOverrun("x".repeat(200), 200)).toBe(0);
  expect(mediaNoteOverrun("x".repeat(500), undefined)).toBe(0);
});

function field(key: string, type: DwFieldType, extra: Partial<DwField> = {}): DwField {
  return { key, label: key, type, tier: "STANDARD", required: false, ...extra };
}

function entity(key: string, fields: DwField[]): DwEntity {
  return { key, name: key, cardinality: "COLLECTION", title: key, description: "", parent: "", labelField: "", fields };
}

const PARTICIPANT = entity("participant", [
  field("artisanRef", "REF", { refModel: "Artisan" }),
  field("recordMediaNote", "TEXT", { maxLength: 200 }),
  field("village", "TEXT")
]);

test("the four fields whose sentence _media_note composes get the control, with the right subject", () => {
  const participant = recordMediaNoteRole(PARTICIPANT, PARTICIPANT.fields[1]);
  expect(participant?.refField.key).toBe("artisanRef");
  expect(participant?.subject).toBe("artisan");
  expect(participant?.linkedRecordType).toBe("artisan");
  expect(participant?.numberedPrefix).toBe("");

  const tool = entity("tool", [
    field("toolRef", "REF", { refModel: "ToolDocumentation" }),
    field("recordMediaNote", "TEXT", { maxLength: 200 })
  ]);
  expect(recordMediaNoteRole(tool, tool.fields[1])?.numberedPrefix).toBe("STAGE_STEP_");
  expect(recordMediaNoteRole(tool, tool.fields[1])?.subject).toBe("tool");

  const setup = entity("workshopSetup", [
    field("craftRef", "REF", { refModel: "Craft" }),
    field("craftMediaNote", "TEXT", { maxLength: 200 })
  ]);
  expect(recordMediaNoteRole(setup, setup.fields[1])?.subject).toBe("craft");

  const product = entity("existingProduct", [
    field("artisanRef", "REF", { refModel: "Artisan" }),
    field("productRef", "REF", { refModel: "ProductDocumentation" }),
    field("recordMediaNote", "TEXT", { maxLength: 200 })
  ]);
  const resolved = recordMediaNoteRole(product, product.fields[2]);
  expect(resolved?.refField.key).toBe("productRef");
  expect(resolved?.subject).toBe("product");
});

test("the process's media note is refused, because a different function composes its sentence", () => {
  const process = entity("traditionalProcess", [
    field("processRef", "REF", { refModel: "Process" }),
    field("recordMediaNote", "TEXT", { maxLength: 200 })
  ]);
  expect(recordMediaNoteRole(process, process.fields[1])).toBeNull();
});

test("nothing else on the entity acquires the control", () => {
  const notes = entity("participant", [
    field("artisanRef", "REF", { refModel: "Artisan" }),
    field("recordNotes", "LONG_TEXT")
  ]);
  expect(recordMediaNoteRole(notes, notes.fields[1])).toBeNull();
  expect(recordMediaNoteRole(PARTICIPANT, PARTICIPANT.fields[2])).toBeNull();
  const retired = entity("participant", [
    field("artisanRef", "REF", { refModel: "Artisan" }),
    field("recordMediaNote", "TEXT", { deprecated: true })
  ]);
  expect(recordMediaNoteRole(retired, retired.fields[1])).toBeNull();
  const orphan = entity("madeUpEntity", [field("recordMediaNote", "TEXT")]);
  expect(recordMediaNoteRole(orphan, orphan.fields[0])).toBeNull();
});

test("the chooser filters as you type and does not ask to be confirmed", () => {
  const source = COMPONENT();
  expect(source, "options are fetched records, so the filter box is explicit").toContain("searchable");
  expect(source, "no Confirm over a change already made").toContain("confirmOnSelect={false}");
  expect(source, "the multi-select primitive, not a hand-rolled list").toContain("<MultiSelectDropdown");
});

test("an over-length selection is refused on screen and never written", () => {
  const source = COMPONENT();
  const apply = source.slice(source.indexOf("const applySelection"), source.indexOf("/** The note itself"));
  expect(apply, "the overrun is measured against the declared bound").toContain(
    "mediaNoteOverrun(composed, field.maxLength)"
  );
  expect(apply, "and nothing is written when it does not fit").toContain("if (overrun) return;");
  expect(apply, "the base is stripped first, or clauses accumulate").toContain("claim || recordCount");
  expect(apply, "never shorten a count to make it fit").not.toContain(".slice(0, field.maxLength");
});

test("the three states a fetched list has are each answered in words", () => {
  const source = COMPONENT();
  expect(source, "the unlinked case is a branch, not a crash").toContain("if (!refId)");
  expect(source).toContain("{role.refField.label}");
  expect(source, "null and [] are different facts").toContain("if (rows === null)");
  expect(source).toContain("Listing the files attached to the");
  expect(source).toContain("No files are listed against the");
  expect(source, "stale text").toContain("Box text is from the last save");
  expect(source, "and the FK-versus-tag case: counted files this list does not show").toContain(
    "aren't listed here"
  );
  expect(source).toContain("could not be listed, so this is a plain box");
});

test("the hydrated value is always editable and is never stranded", () => {
  const source = COMPONENT();
  expect(source, "one box, rendered in every state").toContain("const noteBox = (");
  expect(source).toContain('className="field-input"');
  expect(source).toContain("maxLength={field.maxLength || undefined}");
  expect(source).toContain("Ticking replaces the files already named.");
});

test("the fetch is guarded against its own late answers and against the endpoint's page ceiling", () => {
  const source = COMPONENT();
  expect(source, "a late answer must not win").toContain("mine !== generation.current");
  expect(source).toContain("const PAGE_SIZE = 100");
  expect(source, "every page is walked, so the last file is listed").not.toContain("MAX_PAGES");
  expect(source).toContain("for (let page = 1; ; page += 1)");
  expect(source).toContain("Showing the latest {rows.length} of {total}.");
});
