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

/**
 * "MEDIA ON THE ARTISAN RECORD", AS A MULTI-SELECT — AND THE FOUR WAYS THAT COULD HAVE GONE WRONG.
 *
 * The owner asked for a registry field to become a multi-select. The field is
 * `participant.recordMediaNote`: TEXT, `max_length=200`, and a HYDRATION TARGET whose value is a
 * SENTENCE `_media_note` composes by counting the files attached to the linked Artisan. So unlike
 * every other picker in this app the control cannot store "the thing that was picked" — it has to
 * compose a string, on the client, that agrees with a string the server wrote. Four hazards follow,
 * and every one of them is silent:
 *
 *  1. A GRAMMAR THAT DRIFTS BY ONE WORD. The control offers "use the count of the files listed here",
 *     so a port that spells the same files differently turns a designer pressing "put it back" into a
 *     designer authoring a new value. Pinned below against the exact string the BACKEND's own test
 *     pins (`backend/tests/test_reference_carry.py:1930`).
 *  2. A SUBSET COUNT PRESENTED AS A TOTAL. "Attached to the artisan record: 1 photograph." against a
 *     record holding four is a false claim in a document that goes to a ministry. The pointer clause
 *     exists so the record's own count is never made smaller, and the test asserts the claim survives
 *     verbatim.
 *  3. A SENTENCE THAT GROWS PER CLICK. Without a strip-before-compose the third pick produces two
 *     pointer clauses, overruns 200 characters, and is refused by `coerce_value` on a save the
 *     designer cannot connect to anything they did.
 *  4. A CHOOSER MOUNTED ON THE WRONG FIELD. `traditionalProcess.recordMediaNote` looks identical on
 *     screen and is filled by a DIFFERENT function with a DIFFERENT grammar. Offering this control
 *     there would let one grammar be replaced by another inside a box the report prints verbatim.
 *
 * WHY PART PURE CALL AND PART SOURCE READ — the split `dropdown-sweep-unit.spec.ts` and
 * `existing-media-count-unit.spec.ts` already make, for their reason. The grammar and the role
 * resolution are pure functions and are tested by CALLING them, which is what `mediaNoteGrammar.ts`
 * was extracted for. Whether a call site passes `searchable`, whether a refused length is written
 * anyway, and whether the three empty states are handled live inside a React component, and this
 * repository has no React renderer in its devDependencies — Playwright is the whole of it. Those are
 * read out of the source.
 *
 * WHAT THE SOURCE READS DO NOT PROVE: that a browser paints or announces any of it.
 */

const COMPONENT = () =>
  readFileSync(join(__dirname, "..", "components", "designworkshop", "StageMediaNoteField.tsx"), "utf8");

/** A media row as the grammar reads it. Three keys, because the grammar reads three. */
function row(mediaType: string, extra: Partial<MediaNoteRow> = {}): MediaNoteRow {
  return { mediaType, originalFilename: `${mediaType.toLowerCase()}.bin`, ...extra };
}

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The grammar, against the string the backend pins
 * ──────────────────────────────────────────────────────────────────────────── */

test("the composed count is byte-identical to the sentence the server writes", () => {
  /*
    THE EXACT STRING `backend/tests/test_reference_carry.py:1930` ASSERTS. Not paraphrased and not
    rebuilt from the parts — that would test the port against itself. If this line and the backend's
    ever disagree, the control's "use the count of the files listed here" offer silently rewrites a
    hydrated value into a second spelling of the same fact, and nothing downstream would report it.
  */
  expect(composeRecordCount({ subject: "artisan", rows: [row("IMAGE"), row("AUDIO")] })).toBe(
    "Attached to the artisan record: 1 photograph, 1 audio note."
  );
});

test("the word order is the sentence's order and never the data's", () => {
  // `_media_note` walks `_MEDIA_NOTE_WORDS`, so photographs precede videos precede audio precedes
  // documents precedes files WHATEVER order the rows arrive in. A sort here would be a different
  // string for the same files, which is the one thing the port may not produce.
  const shuffled = [row("OTHER"), row("PDF"), row("AUDIO"), row("VIDEO"), row("IMAGE")];
  expect(mediaNoteBreakdown(shuffled)).toBe("1 photograph, 1 video, 1 audio note, 1 document, 1 file");
});

test("PDF and DOCUMENT are one word, and a token this build has never heard of is still counted", () => {
  // Collapsing PDF+DOCUMENT is the server's decision: the difference is a mime type, not a fact a
  // reader of the printed report can act on.
  expect(mediaNoteBreakdown([row("PDF"), row("DOCUMENT")])).toBe("2 documents");
  // And the OTHER bucket absorbs anything the enum has gained since the table was written. Dropping
  // it would produce a sentence saying a record carries three files when it carries five, with
  // nothing anywhere to contradict it.
  expect(mediaNoteBreakdown([row("HOLOGRAM"), row("OTHER")])).toBe("2 files");
  // A row with no type at all lands in the same bucket, not on the floor.
  expect(mediaNoteBreakdown([{ mediaType: null }])).toBe("1 file");
});

test("nothing countable is null and never a sentence saying zero", () => {
  // The server returns None, so the key is ABSENT from the hydration payload and the box stays blank.
  // "0 files" would be a claim, and the wrong one the moment somebody attaches a photograph without
  // re-saving the stage.
  expect(composeRecordCount({ subject: "artisan", rows: [] })).toBeNull();
  expect(composePointerNote({ subject: "artisan", base: "anything", rows: [] })).toBeNull();
});

test("a measurement-grid frame is neither counted nor offered, under the one shared spelling", () => {
  // ONE SPELLING ACROSS FOUR SURFACES. The web writes it, Android writes it, the server sorts on it
  // and `_media_note` subtracts it. The grammar module declares its own copy so a unit spec can
  // import it without pulling a camera and a vision request in; this is what keeps the two equal.
  expect(MEDIA_NOTE_GRID_PURPOSE).toBe(MEASUREMENT_GRID_PURPOSE);

  const grid = row("IMAGE", { extraMetadata: { purpose: MEASUREMENT_GRID_PURPOSE } });
  const rows = [row("IMAGE"), grid];
  // Subtracted from the count — a sheet of ruled paper is not footage of the subject, and counting it
  // would overstate by one on exactly the records that were measured most carefully.
  expect(mediaNoteBreakdown(rows)).toBe("1 photograph");
  // And withheld from the OPTIONS, which is the half only this client can get wrong: `GET /media`
  // does not subtract them, so a chooser built straight off the fetch would offer a frame the
  // sentence deliberately excludes and disagree with its own field by one.
  expect(countableMediaRows(rows)).toHaveLength(1);
  // A non-object, a list, and a different purpose are all "not a grid frame" rather than errors.
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
  // The artisan's lambda passes no prefix, so the identical files produce no clause. Passing one
  // everywhere would make a composed artisan sentence differ from the hydrated one on any record
  // whose files happen to be named that way.
  expect(composeRecordCount({ subject: "artisan", rows: steps })).toBe(
    "Attached to the artisan record: 3 photographs."
  );
  // "1 documents" reads backwards and is the server's string: the subject of the clause is the count.
  // Ported verbatim rather than repaired, because it is compared against values already stored.
  expect(
    composeRecordCount({ subject: "tool", rows: [steps[0]], numberedPrefix: "STAGE_STEP_" })
  ).toContain("of which 1 documents the making in order");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. Narrowing, which may never make the record's own count smaller
 * ──────────────────────────────────────────────────────────────────────────── */

const FULL = "Attached to the artisan record: 4 photographs, 1 audio note.";

test("narrowing keeps the record's own count verbatim and adds a second sentence", () => {
  const narrowed = composePointerNote({ subject: "artisan", base: FULL, rows: [row("IMAGE")] });
  // THE WHOLE DESIGN, IN ONE ASSERTION. A subset count printed as the record's total is a false claim
  // in a ministry document, and this field exists so a reader knows what to ask FOR.
  expect(narrowed).toBe(`${FULL} See in particular: 1 photograph.`);
  expect(narrowed?.startsWith(FULL)).toBe(true);
  // And it must never be the shape the obvious implementation would produce.
  expect(narrowed).not.toBe("Attached to the artisan record: 1 photograph.");
});

test("picking again replaces the clause instead of growing the sentence", () => {
  const once = composePointerNote({ subject: "artisan", base: FULL, rows: [row("IMAGE")] }) ?? "";
  const twice = composePointerNote({ subject: "artisan", base: once, rows: [row("IMAGE"), row("AUDIO")] }) ?? "";
  expect(twice).toBe(`${FULL} See in particular: 1 photograph, 1 audio note.`);
  // One clause, not two. Without the strip the third click overruns 200 characters and the save is
  // refused against a box the designer never typed in.
  expect(twice.match(/See in particular/g)).toHaveLength(1);
  // And it is genuinely idempotent: composing the same selection over its own output is a fixed point.
  expect(composePointerNote({ subject: "artisan", base: twice, rows: [row("IMAGE"), row("AUDIO")] })).toBe(twice);
});

test("clearing the selection leaves the record's own count and nothing else", () => {
  const narrowed = composePointerNote({ subject: "artisan", base: FULL, rows: [row("IMAGE")] }) ?? "";
  expect(stripPointerNote(narrowed)).toBe(FULL);
  expect(hasPointerNote(narrowed)).toBe(true);
  expect(hasPointerNote(FULL)).toBe(false);
});

test("a designer's own prose is not mistaken for a clause the control wrote", () => {
  // The strip is anchored at the END. A designer who has written the words mid-sentence keeps them —
  // this box legitimately holds prose about footage, and eating part of it would be a control
  // silently editing an answer it did not author.
  const prose = "See in particular: the audio, though the video is longer. Ask the researcher.";
  expect(stripPointerNote(prose)).toBe(prose);
  // With no claim to keep, the sentence makes no assertion about the record at all — which is also
  // what a TRUNCATED listing gets, because a count over part of a list is not a count.
  expect(composePointerNote({ subject: "artisan", base: "", rows: [row("AUDIO")] })).toBe(
    "Of the media attached to the artisan record, see: 1 audio note."
  );
});

test("the sentence written over an empty base is itself strippable, so the control stays idempotent", () => {
  /*
    THE BRANCH THIS SUITE PINNED THE OUTPUT OF AND NEVER ROUND-TRIPPED, which is how it stayed green
    over a real defect. `composePointerNote` has two forms; `stripPointerNote` matched one. Over an
    empty base — a blank box on a TRUNCATED listing — the second form was returned untouched by the
    strip, so the next tick composed a clause ONTO it and the stored sentence permanently carried a
    stale first clause contradicting its own second one:

        tick 1  Of the media attached to the artisan record, see: 1 photograph.
        tick 2  Of the media attached to the artisan record, see: 1 photograph. See in particular: 2 photographs.

    `hasPointerNote` was false after tick 1 as well, so the panel's on-screen promise that ticking
    again REPLACES the clause was neither shown nor true. Both halves are asserted here.
  */
  const first = composePointerNote({ subject: "artisan", base: "", rows: [row("IMAGE")] }) ?? "";
  expect(first).toBe("Of the media attached to the artisan record, see: 1 photograph.");
  expect(hasPointerNote(first), "the panel must be able to say the box already names files").toBe(true);
  expect(stripPointerNote(first), "there is no claim about the record to keep, so nothing is kept").toBe("");

  // A second tick over the control's own output REPLACES, never appends — the fixed point the
  // clause form is already tested for, now on the branch that did not have it.
  const second = composePointerNote({ subject: "artisan", base: first, rows: [row("IMAGE"), row("AUDIO")] });
  expect(second).toBe("Of the media attached to the artisan record, see: 1 photograph, 1 audio note.");
  expect(second?.match(/see:/gi)).toHaveLength(1);
  expect(second?.includes("See in particular")).toBe(false);

  // Composing the same selection over its own output is a fixed point, which is the property the
  // clause form is already tested for and the one this branch did not have.
  expect(composePointerNote({ subject: "artisan", base: first, rows: [row("IMAGE")] })).toBe(first);
  // And the clause form is unaffected by the second pattern: a real claim is still kept verbatim.
  expect(stripPointerNote(`${FULL} See in particular: 1 photograph.`)).toBe(FULL);
  // A base with no terminator gains exactly one, not two.
  expect(composePointerNote({ subject: "artisan", base: "Four files here", rows: [row("AUDIO")] })).toBe(
    "Four files here. See in particular: 1 audio note."
  );
});

test("the field's bound is measured, and an absent bound means unbounded rather than zero", () => {
  expect(mediaNoteOverrun("x".repeat(201), 200)).toBe(1);
  expect(mediaNoteOverrun("x".repeat(200), 200)).toBe(0);
  // `field_to_dict` emits only non-default keys, so a missing `maxLength` is "no bound declared".
  // Reading it as zero would refuse every value on every field that does not declare one.
  expect(mediaNoteOverrun("x".repeat(500), undefined)).toBe(0);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. Which fields get the control, read off the hydration table
 * ──────────────────────────────────────────────────────────────────────────── */

function field(key: string, type: DwFieldType, extra: Partial<DwField> = {}): DwField {
  return { key, label: key, type, tier: "STANDARD", required: false, ...extra };
}

/** An entity whose key and ref field key are REAL, so `referenceHydrationFor` hits the real table. */
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
  // `subject` is the literal the server passes to `_media_note`; `linkedRecordType` is the `/media`
  // tag. They are the same word here and are two different contracts — see the table's own note.
  expect(participant?.subject).toBe("artisan");
  expect(participant?.linkedRecordType).toBe("artisan");
  // Only the tool's call passes a prefix, so only the tool's sentence can grow the making clause.
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

  // `existingProduct` carries TWO mappings — the large `productRef` and a one-pair `artisanRef` — so
  // this is also the case that proves the resolution reads the mapping rather than picking the first
  // REF field it sees.
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
  /*
    THE REFUSAL, PINNED. `traditionalProcess.recordMediaNote` is TEXT, 200 characters, hydrated from a
    ref field and labelled like the other four — and it is filled by `_process_media_note`, whose
    grammar is "N on the process itself, N across N step(s)". This control composes `_media_note`'s
    grammar and nothing else, so offering it there would let a designer replace one grammar with
    another inside a box the report prints verbatim. That function is also dormant: `MediaFile` has no
    `processId`, so it returns None for every process and the box is blank today.
  */
  const process = entity("traditionalProcess", [
    field("processRef", "REF", { refModel: "Process" }),
    field("recordMediaNote", "TEXT", { maxLength: 200 })
  ]);
  expect(recordMediaNoteRole(process, process.fields[1])).toBeNull();
});

test("nothing else on the entity acquires the control", () => {
  // A field the mapping fills from a NON-media-note source key. `notes -> recordNotes` lands on the
  // same entity from the same ref field, and a role that matched on the target alone would put a file
  // chooser on a free-prose box.
  const notes = entity("participant", [
    field("artisanRef", "REF", { refModel: "Artisan" }),
    field("recordNotes", "LONG_TEXT")
  ]);
  expect(recordMediaNoteRole(notes, notes.fields[1])).toBeNull();
  // A field the mapping does not fill at all.
  expect(recordMediaNoteRole(PARTICIPANT, PARTICIPANT.fields[2])).toBeNull();
  // A deprecated box: writing a new value into a field the registry has stopped asking for would
  // still print.
  const retired = entity("participant", [
    field("artisanRef", "REF", { refModel: "Artisan" }),
    field("recordMediaNote", "TEXT", { deprecated: true })
  ]);
  expect(recordMediaNoteRole(retired, retired.fields[1])).toBeNull();
  // An entity with no mapping at all — the fail-closed direction: the field stays the text box it is
  // rather than gaining a chooser over some other record's files.
  const orphan = entity("madeUpEntity", [field("recordMediaNote", "TEXT")]);
  expect(recordMediaNoteRole(orphan, orphan.fields[0])).toBeNull();
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. The control's own call site — source reads
 * ──────────────────────────────────────────────────────────────────────────── */

test("the chooser filters as you type and does not ask to be confirmed", () => {
  const source = COMPONENT();
  // The options are RECORDS — one per uploaded file — so `searchable` is this call site's decision.
  // Left to `SEARCH_THRESHOLD` the same control would gain and lose its filter box as a record
  // accumulated attachments.
  expect(source, "options are fetched records, so the filter box is explicit").toContain("searchable");
  // The effect of each tick is on screen in the box above as it happens, so a Confirm button would be
  // a button over a change that has already happened — and advancing focus away from a control still
  // being adjusted is wrong.
  expect(source, "no Confirm over a change already made").toContain("confirmOnSelect={false}");
  expect(source, "the multi-select primitive, not a hand-rolled list").toContain("<MultiSelectDropdown");
});

test("an over-length selection is refused on screen and never written", () => {
  const source = COMPONENT();
  // The order of these two statements is the finding: `setRefusedOverrun` then `if (overrun) return;`
  // BEFORE `onChange`. Reversed, the box would hold a value `coerce_value` refuses and `save_stage`
  // would restore the old one under an error against a box that had silently reverted.
  const apply = source.slice(source.indexOf("const applySelection"), source.indexOf("/** The note itself"));
  expect(apply, "the overrun is measured against the declared bound").toContain(
    "mediaNoteOverrun(composed, field.maxLength)"
  );
  expect(apply, "and nothing is written when it does not fit").toContain("if (overrun) return;");
  expect(apply, "the base is stripped first, or clauses accumulate").toContain("claim || recordCount");
  // Truncating to fit would be worse than refusing: a sentence shortened to fit is a count nobody can
  // tell is wrong.
  expect(apply, "never shorten a count to make it fit").not.toContain(".slice(0, field.maxLength");
});

test("the three states a fetched list has are each answered in words", () => {
  const source = COMPONENT();
  // NOT LINKED: no record, so no fetch and no chooser — and the reason is named, with the picker that
  // fixes it.
  expect(source, "the unlinked case is a branch, not a crash").toContain("if (!refId)");
  expect(source).toContain("{role.refField.label}");
  // STILL LOADING: `rows === null` is "not answered yet" and is not the same state as an empty list.
  expect(source, "null and [] are different facts").toContain("if (rows === null)");
  expect(source).toContain("Listing the files attached to the");
  // NOTHING ATTACHED: said out loud, and with the FK-versus-tag disagreement named rather than left
  // as an inexplicable blank beside a filled box.
  expect(source).toContain("No files are listed against the");
  expect(source).toContain("linked in a way this listing does not follow");
  // AND A FAILED FETCH IS NOT AN EMPTY LIST. "This record has no media" and "we could not ask" are
  // different facts and only one is a reason to stop looking.
  expect(source).toContain("could not be listed, so this is a plain box");
});

test("the hydrated value is always editable and is never stranded", () => {
  const source = COMPONENT();
  // The box is a real input in EVERY branch — the control is additive, and a value that matches no
  // option (hand-typed prose, a sentence from a record since changed) must stay readable and
  // correctable rather than being replaced by a dropdown that cannot draw it.
  expect(source, "one box, rendered in every state").toContain("const noteBox = (");
  expect(source).toContain('className="field-input"');
  expect(source).toContain("maxLength={field.maxLength || undefined}");
  // And a later visit is told what cannot be recovered: WHICH files an earlier clause named is stored
  // nowhere, so ticking replaces the clause rather than adding to it.
  expect(source).toContain("Which files those were is recorded nowhere");
});

test("the fetch is guarded against its own late answers and against the endpoint's page ceiling", () => {
  const source = COMPONENT();
  // The generation counter every list surface in this app uses: `listResource` takes no AbortSignal,
  // so the late answer is IGNORED rather than cancelled. Without it, re-pointing the picker mid-flight
  // lands the previous artisan's files under the new artisan's id.
  expect(source, "a late answer must not win").toContain("mine !== generation.current");
  // 100 is the endpoint's declared maximum, and a bounded walk past it is what keeps a count honest:
  // past the cap the control says the listing is truncated instead of printing a page size as a total.
  expect(source).toContain("const PAGE_SIZE = 100");
  expect(source).toContain("const MAX_PAGES = 3");
  expect(source).toContain("most recent of {total} attached files");
});
