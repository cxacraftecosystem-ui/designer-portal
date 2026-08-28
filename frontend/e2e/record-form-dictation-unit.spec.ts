import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  createBrowserRecognition,
  describeSpeechError,
  DICTATION_LANGUAGES,
  NO_RECOGNISER_SENTENCE,
  startRecognition,
  type SpeechRecognitionLike
} from "@/components/dictation/onDeviceSpeech";
import {
  appendStoredParagraph,
  decodeStoredRichText,
  encodeStoredRichText
} from "@/components/richtext/storedRichText";
import { fromStored, toStored, toPlain } from "@/lib/richText";

/**
 * DICTATION AND RICH TEXT ON THE RECORD FORMS — and the two things that must NOT have moved.
 *
 * The requirement was "the dictate option along with the rich text formatting for the bigger fields
 * should be there" on the pages that are not the design workshop, refined to: on-device recognition
 * only (no server transcription for record forms), rich text on the LARGER boxes only, and no column
 * migration — the affected columns stay `String?`.
 *
 * That shape creates exactly three ways to ship a silent defect, and this spec is one section per
 * way:
 *
 *   1. **A voice recording leaving the device from a record form.** The stage form's dictation
 *      button has a server rung gated on a workshop's recorded `dictationConsent`; a record has no
 *      workshop, and the id-less route that would take a clip without asking is 410 GONE on purpose.
 *      So the record-form control must have no transport at all — asserted by reading its source for
 *      any network primitive, because "it happens not to call fetch today" is not a property a type
 *      can carry.
 *   2. **A document stringified into a searched column.** `fromStored` here, `from_json` on the
 *      server and `RichText.fromStored` on Android all read a `str` as PLAIN PROSE with no
 *      `json.loads` attempt — each says why in its own comment — so a JSON string in
 *      `Artisan.notes` comes back out as literal braces in a CSV, a report and the review panel.
 *      `encodeStoredRichText` therefore writes prose whenever the document is expressible as prose,
 *      and this spec pins that rule from both directions.
 *   3. **A change to the shared recogniser breaking the stage form.** Decoupling moved the Web
 *      Speech lifecycle out of `components/designworkshop/Dictation.tsx` into a module both buttons
 *      call. The stage form's behaviour had to be identical afterwards, so the lifecycle is driven
 *      here against a fake recogniser, and the stage form's own wiring is read back out of source.
 *
 * WHY A NODE SPEC AND NOT A BROWSER RUN. This repository has no React renderer in its
 * devDependencies — Playwright is the whole of it — so mounting a component is not available, and
 * `capped-lists-unit.spec.ts`, `derived-fields-unit.spec.ts` and `discarded-work-unit.spec.ts` all
 * read their structural half out of the source for the same reason. The encode/decode half and the
 * recogniser half are genuinely EXECUTED. What none of this proves is that a browser paints the
 * microphone; that belongs in a signed-in spec against the dev server and is named at the bottom.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const ON_DEVICE_SPEECH = read("components", "dictation", "onDeviceSpeech.ts");
const ON_DEVICE_BUTTON = read("components", "dictation", "OnDeviceDictationButton.tsx");
const EDITOR = read("components", "designworkshop", "RichTextEditor.tsx");
const WORKSHOP_DICTATION = read("components", "designworkshop", "Dictation.tsx");
const FIELD_INPUT = read("components", "designworkshop", "FieldInput.tsx");
const RICH_TEXT_FIELD = read("components", "richtext", "RichTextField.tsx");
const ARTISAN_FORM = read("components", "forms", "ArtisanForm.tsx");
const PRODUCT_FORM = read("components", "forms", "ProductForm.tsx");
const TOOL_FORM = read("components", "forms", "ToolForm.tsx");
const PROCESS_FORM = read("components", "forms", "ProcessForm.tsx");

/*
 * THE QUESTIONNAIRE FAMILY AND THE THREE REMAINING RECORD PAGES — added 2026-08-28.
 *
 * The owner widened the requirement twice after the first sweep landed: *"all the record pages
 * should have dictation options available, wherever applicable so as to reduce the friction as much
 * as possible"*, then *"dictation should be a default for other record pages as well."* These six
 * files are where that second sweep went, and section 4 below is what stops it quietly coming back
 * out.
 *
 * BOTH questionnaire families are read here on purpose. `/questionnaire` (singular) is the ministry
 * instrument and `/questionnaires` (plural) is the designer-owned form builder; they are separate
 * features with separate models and must never be unified, so a rule applied to one is not applied
 * to the other unless somebody applies it twice. That is exactly the drift a register catches and a
 * review does not.
 */
const Q_SINGULAR = read("app", "(protected)", "questionnaire", "page.tsx");
const Q_LIST = read("app", "(protected)", "questionnaires", "page.tsx");
const Q_DETAIL = read("app", "(protected)", "questionnaires", "[id]", "page.tsx");
const Q_ANSWER = read("app", "(protected)", "questionnaires", "[id]", "answer", "page.tsx");
const MEDIA_PAGE = read("app", "(protected)", "media", "page.tsx");
const WORKSHOP_PAGE = read("app", "(protected)", "workshops", "page.tsx");

/* ────────────────────────────────────────────────────────────────────────────
 * 1. Nothing a record form hears may leave the device
 * ──────────────────────────────────────────────────────────────────────────── */

test("the on-device dictation path contains no transport of any kind", () => {
  for (const [name, source] of [
    ["onDeviceSpeech.ts", ON_DEVICE_SPEECH],
    ["OnDeviceDictationButton.tsx", ON_DEVICE_BUTTON]
  ] as const) {
    // Comments in these files DISCUSS the server rung at length — that is the point of them — so the
    // search is for the primitives themselves in code position, not for the words.
    const code = source
      .replace(/\/\*[\s\S]*?\*\//g, "")
      .split("\n")
      .filter((line) => !line.trim().startsWith("*") && !line.trim().startsWith("//"))
      .join("\n");
    expect(code, `${name} must not fetch`).not.toMatch(/\bfetch\s*\(/);
    expect(code, `${name} must not record audio`).not.toMatch(/MediaRecorder/);
    expect(code, `${name} must not open a microphone stream`).not.toMatch(/getUserMedia/);
    expect(code, `${name} must not import the design-workshop API helpers`).not.toMatch(/lib\/designWorkshops/);
    expect(code, `${name} must not name a dictate route`).not.toMatch(/\/dictate/i);
  }
});

test("no record form imports the workshop dictation button or the workshop API", () => {
  for (const [name, source] of [
    ["ArtisanForm", ARTISAN_FORM],
    ["ProductForm", PRODUCT_FORM],
    ["ToolForm", TOOL_FORM],
    ["ProcessForm", PROCESS_FORM]
  ] as const) {
    expect(source, `${name} must not import DictationButton`).not.toMatch(/from "@\/components\/designworkshop\/Dictation"/);
    expect(source, `${name} must not import dictateAudio`).not.toMatch(/dictateAudio/);
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The columns stay plain-compatible
 * ──────────────────────────────────────────────────────────────────────────── */

test("an unformatted document is stored as the prose it is, byte for byte", () => {
  const typed = fromStored("The warp is sized with rice starch.\nThe weft is undyed.");
  expect(encodeStoredRichText(toStored(typed))).toBe(
    "The warp is sized with rice starch.\nThe weft is undyed."
  );
  // Which is exactly what the report builder, the CSV exports and eleven `contains` search clauses
  // would have read out of the column before this change existed.
  expect(encodeStoredRichText(toStored(typed))).toBe(toPlain(typed));
});

test("a FORMATTED document is stored as JSON, and comes back as the same document", () => {
  const bolded = toStored(
    fromStored({ blocks: [{ kind: "PARAGRAPH", spans: [{ text: "Sized", marks: ["BOLD"] }, { text: " with starch." }] }] })
  );
  const stored = encodeStoredRichText(bolded);
  expect(stored.startsWith("{"), "a formatted document has to be encoded").toBeTruthy();

  const reopened = decodeStoredRichText(stored);
  expect(typeof reopened, "the editor must be handed an object, never the JSON string").toBe("object");
  expect(toStored(fromStored(reopened))).toEqual(bolded);
});

test("a table, an image, a heading, a list and an alignment each force the encoded form", () => {
  // The polarity that matters: a NEW block kind must default to "store as JSON". Storing it as prose
  // would flatten a researcher's table into pipe-separated lines on save, and they would not find
  // out until the report was printed.
  const cases: Array<[string, unknown]> = [
    ["heading", { blocks: [{ kind: "HEADING", level: 2, spans: [{ text: "Dyeing" }] }] }],
    ["bullet", { blocks: [{ kind: "BULLET_ITEM", spans: [{ text: "Indigo" }] }] }],
    ["quote", { blocks: [{ kind: "QUOTE", spans: [{ text: "As my father did." }] }] }],
    ["align", { blocks: [{ kind: "PARAGRAPH", align: "CENTER", spans: [{ text: "Centred" }] }] }],
    ["table", { blocks: [{ kind: "TABLE", spans: [], rows: [[[{ text: "a" }], [{ text: "b" }]]] }] }],
    ["image", { blocks: [{ kind: "IMAGE", media: "media-1", widthPct: 70, spans: [{ text: "The loom" }] }] }]
  ];
  for (const [label, doc] of cases) {
    expect(encodeStoredRichText(toStored(fromStored(doc))).startsWith("{"), `${label} must be encoded`).toBeTruthy();
  }
});

test("decoding leaves prose alone, including prose that merely begins with a brace", () => {
  expect(decodeStoredRichText(null)).toBeNull();
  expect(decodeStoredRichText("Plain notes.")).toBe("Plain notes.");
  expect(decodeStoredRichText("{not json at all")).toBe("{not json at all");
  // Valid JSON, but not a block document. Read as what it is rather than as an empty document,
  // which is how a pasted configuration snippet in a notes box would otherwise be silently blanked.
  expect(decodeStoredRichText('{"a":1}')).toBe('{"a":1}');
});

test('a multi-note column keeps its blank-line contract under join="paragraph"', () => {
  // `Artisan.notes` and `ProcessStep.notes` are SPLIT on blank lines by `MultiNoteField` here and by
  // `MultiNoteInput` in Android's MainActivity.kt. Single newlines would silently collapse three
  // notes into one the next time the record was opened on a handset.
  const doc = toStored(fromStored("Note one.\nNote two.\nNote three."));
  expect(encodeStoredRichText(doc, "paragraph")).toBe("Note one.\n\nNote two.\n\nNote three.");
  expect(encodeStoredRichText(doc, "line")).toBe("Note one.\nNote two.\nNote three.");
});

test("the EXIF remark is appended INTO a document, and onto prose exactly as it always was", () => {
  // The prose branch must be byte-for-byte `appendRemarksWithExif`, or every unformatted record
  // changes shape on its next save for no reason.
  expect(appendStoredParagraph("Woven in March.", "Photo 1 · 2026-03-04")).toBe(
    "Woven in March.\n\nPhoto 1 · 2026-03-04"
  );
  expect(appendStoredParagraph(null, "Photo 1")).toBe("Photo 1");
  expect(appendStoredParagraph("Woven in March.", "")).toBe("Woven in March.");
  expect(appendStoredParagraph(null, "")).toBeNull();

  // The document branch is the bug this helper exists to prevent: string-concatenating onto JSON
  // produces a value that is neither valid JSON nor readable prose.
  const stored = encodeStoredRichText(
    toStored(fromStored({ blocks: [{ kind: "PARAGRAPH", spans: [{ text: "Woven", marks: ["BOLD"] }] }] }))
  );
  const appended = appendStoredParagraph(stored, "Photo 1\nPhoto 2");
  expect(appended?.startsWith("{"), "still a document").toBeTruthy();
  expect(toPlain(fromStored(decodeStoredRichText(appended)))).toBe("Woven\nPhoto 1\nPhoto 2");
});

test("the three forms that machine-append EXIF use the document-aware helper", () => {
  for (const [name, source] of [
    ["ArtisanForm", ARTISAN_FORM],
    ["ProductForm", PRODUCT_FORM],
    ["ToolForm", TOOL_FORM]
  ] as const) {
    expect(source, `${name} still concatenates onto a possibly-JSON column`).not.toMatch(/appendRemarksWithExif\(/);
    expect(source, `${name} must append through appendStoredParagraph`).toMatch(/appendStoredParagraph\(/);
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The stage form must behave exactly as it did
 * ──────────────────────────────────────────────────────────────────────────── */

test("the workshop dictation button still requires a workshop id and still has its server rung", () => {
  expect(WORKSHOP_DICTATION, "workshopId must stay required and undefaulted").toMatch(/\n {2}workshopId: string;/);
  expect(WORKSHOP_DICTATION, "the consent-gated upload must still be here").toMatch(/dictateAudio\(blob, languageRef\.current, workshopId\)/);
  expect(WORKSHOP_DICTATION, "a local-only workshop must still be refused before recording").toMatch(
    /isLocalWorkshopId\(workshopId\)/
  );
  expect(WORKSHOP_DICTATION, "the deployment probe must still run").toMatch(/serverOffersRoute\(DW_DICTATE_PATH\)/);
  // And it must still be the ONLY component in the tree that can upload a clip: the on-device button
  // may DISCUSS it in a comment — it does, at length — but it may not import it.
  expect(ON_DEVICE_BUTTON).not.toMatch(/import[\s\S]{0,160}designworkshop\/Dictation/);
});

test("every stage call site still passes a workshop id, so no stage silently loses the server rung", () => {
  const calls = FIELD_INPUT.match(/<DictationButton[^>]*\/>/g) ?? [];
  expect(calls.length, "FieldInput's dictation buttons").toBeGreaterThan(0);
  for (const call of calls) expect(call, "a stage dictation button with no workshopId").toMatch(/workshopId=\{workshopId\}/);
  expect(FIELD_INPUT, "the RICH_TEXT branch must keep threading the id into the editor").toMatch(
    /<RichTextEditor[\s\S]{0,600}?workshopId=\{workshopId\}/
  );
});

test("the editor picks its dictation button by whether it was told a workshop", () => {
  expect(EDITOR, "workshopId must be optional now").toMatch(/\n {2}workshopId\?: string;/);
  expect(EDITOR, "with an id, the consent-gated button").toMatch(
    /workshopId \? \(\s*<DictationButton fieldLabel=\{ariaLabel \?\? "this field"\} workshopId=\{workshopId\}/
  );
  expect(EDITOR, "without one, the on-device button").toMatch(/<OnDeviceDictationButton fieldLabel=/);
  // One insertion path for both, so a fix to caret handling cannot land on one surface only.
  expect(EDITOR).toMatch(/const commitDictated = useCallback\(/);
  expect((EDITOR.match(/onCommit=\{commitDictated\}/g) ?? []).length).toBe(2);
});

test("the record forms mount the editor through RichTextField, which passes no workshop id", () => {
  expect(RICH_TEXT_FIELD, "RichTextField must never thread a workshop id").not.toMatch(/workshopId[=:]/);
  expect(RICH_TEXT_FIELD, "the form reads the value through a hidden input, as before").toMatch(
    /<input type="hidden" name=\{name\} value=\{submitValue\} \/>/
  );
});

/* ────────────────────────────────────────────────────────────────────────────
 * The shared recogniser lifecycle, actually driven
 * ──────────────────────────────────────────────────────────────────────────── */

/** The four callbacks a `SpeechRecognition` fires, under test control. */
class FakeRecognition implements SpeechRecognitionLike {
  lang = "";
  continuous = false;
  interimResults = false;
  maxAlternatives = 0;
  started = 0;
  stopped = 0;
  throwOnStart = false;
  onresult: SpeechRecognitionLike["onresult"] = null;
  onerror: SpeechRecognitionLike["onerror"] = null;
  onend: SpeechRecognitionLike["onend"] = null;
  onaudiostart: SpeechRecognitionLike["onaudiostart"] = null;
  start() {
    this.started += 1;
    if (this.throwOnStart) throw new Error("InvalidStateError");
  }
  stop() {
    this.stopped += 1;
  }
  abort() {
    this.stopped += 1;
  }
}

let latest: FakeRecognition | null = null;

test.beforeEach(() => {
  latest = null;
  (globalThis as unknown as { window: unknown }).window = {
    SpeechRecognition: function () {
      latest = new FakeRecognition();
      return latest;
    }
  };
});

test.afterEach(() => {
  delete (globalThis as unknown as { window?: unknown }).window;
});

function harness() {
  const phrases: string[] = [];
  const interims: string[] = [];
  const problems: string[] = [];
  const stops: string[] = [];
  const recognition = createBrowserRecognition({
    language: "or-IN",
    onPhrase: (t) => phrases.push(t),
    onInterim: (t) => interims.push(t),
    onProblem: (s) => problems.push(s),
    onStopped: (r) => stops.push(r)
  });
  expect(recognition).not.toBeNull();
  return { recognition: recognition as SpeechRecognitionLike, phrases, interims, problems, stops };
}

test("only FINAL results are committed, and interims are drawn separately", () => {
  const { recognition, phrases, interims } = harness();
  expect(recognition.lang).toBe("or-IN");
  expect(recognition.continuous, "a spoken paragraph has pauses in it").toBeTruthy();
  expect(recognition.interimResults, "without this the button looks dead for three seconds").toBeTruthy();

  recognition.onresult?.({
    resultIndex: 0,
    results: { length: 1, 0: { isFinal: false, length: 1, 0: { transcript: "the wharf is", confidence: 0.4 } } }
  });
  recognition.onresult?.({
    resultIndex: 0,
    results: { length: 1, 0: { isFinal: true, length: 1, 0: { transcript: " the warp is sized ", confidence: 0.9 } } }
  });
  expect(phrases, "the recogniser's first guess must never be committed").toEqual(["the warp is sized"]);
  expect(interims).toEqual(["the wharf is", ""]);
});

test("iteration starts at resultIndex, so a long dictation does not repeat itself", () => {
  const { recognition, phrases } = harness();
  recognition.onresult?.({
    resultIndex: 1,
    results: {
      length: 2,
      0: { isFinal: true, length: 1, 0: { transcript: "already committed", confidence: 1 } },
      1: { isFinal: true, length: 1, 0: { transcript: "and this one", confidence: 1 } }
    }
  });
  expect(phrases).toEqual(["and this one"]);
});

test("a deliberate stop says nothing; the four real failures each say something different", () => {
  const { recognition, problems, stops } = harness();
  recognition.onerror?.({ error: "aborted" });
  expect(problems, "narrating every normal stop as a failure trains people to ignore the line").toEqual([]);
  expect(stops).toEqual(["error"]);

  recognition.onerror?.({ error: "not-allowed" });
  recognition.onerror?.({ error: "no-speech" });
  recognition.onerror?.({ error: "audio-capture" });
  recognition.onerror?.({ error: "network" });
  expect(problems.length).toBe(4);
  expect(new Set(problems).size, "four distinct next moves, not one catch-all").toBe(4);
  expect(problems[0]).toContain("Allow it for this site");
  expect(problems[2]).toContain("No microphone was found");

  // The unknown case still names the code rather than saying "dictation failed".
  expect(describeSpeechError("service-not-allowed")).toBe(describeSpeechError("not-allowed"));
  expect(describeSpeechError("wobbly")).toContain("(wobbly)");
});

test("onend releases the handle and onerror does not, because onend follows onerror", () => {
  const { recognition, stops } = harness();
  recognition.onerror?.({ error: "network" });
  recognition.onend?.();
  expect(stops).toEqual(["error", "end"]);
});

test("Safari's double-tap throw is reported, so the button cannot stick on Stop", () => {
  const { recognition } = harness();
  expect(startRecognition(recognition)).toBeTruthy();
  (recognition as FakeRecognition).throwOnStart = true;
  expect(startRecognition(recognition), "false is how the caller knows to put the button back").toBeFalsy();
  expect(latest?.started).toBe(2);
});

test("the whole control is withheld, with a reason, where the browser has no recogniser", () => {
  delete (globalThis as unknown as { window?: unknown }).window;
  expect(createBrowserRecognition({ language: "en-IN", onPhrase: () => {}, onInterim: () => {}, onProblem: () => {}, onStopped: () => {} })).toBeNull();

  // And the sentence that is drawn instead is not a disabled button. This repository's most-repeated
  // defect is a control that appears to work and does nothing; a dead microphone on Firefox would be
  // exactly that, and hiding it with no explanation reads as a broken build.
  expect(NO_RECOGNISER_SENTENCE).toContain("Firefox");
  expect(NO_RECOGNISER_SENTENCE).toContain("Type the answer in");
  expect(ON_DEVICE_BUTTON, "the absent branch must render the sentence, not a disabled button").toMatch(
    /availability === "absent"[\s\S]{0,600}?\{NO_RECOGNISER_SENTENCE\}/
  );
  expect(ON_DEVICE_BUTTON, "and it must not draw a disabled mic instead").not.toMatch(/disabled=\{true\}/);
});

test("the language list moved out of the workshop module and kept its storage key", () => {
  expect(DICTATION_LANGUAGES.map((entry) => entry.value)).toContain("or-IN");
  expect(ON_DEVICE_SPEECH, "one key, both surfaces — a researcher re-picking Odia per screen is the defect").toContain(
    '"field_repo_dictation_language"'
  );
  expect(WORKSHOP_DICTATION, "the workshop module must not keep a second copy of the list").not.toMatch(
    /const DICTATION_LANGUAGES/
  );
});

/* ────────────────────────────────────────────────────────────────────────────
 * The sweep: which boxes got what
 * ──────────────────────────────────────────────────────────────────────────── */

test("every qualifying record-form box has a control, and the skipped ones stay skipped", () => {
  // Larger narrative boxes — editor with the on-device mic inside it.
  for (const name of ["notes"]) expect(ARTISAN_FORM).toMatch(new RegExp(`<RichTextField[\\s\\S]{0,200}?name="${name}"`));
  for (const name of ["rawMaterialsUsed", "mainToolsUsed", "productFunctionUse", "remarks"]) {
    expect(PRODUCT_FORM, `${name} must have the editor`).toMatch(new RegExp(`name="${name}"[\\s\\S]{0,300}?onDirty`));
    expect(PRODUCT_FORM).toMatch(new RegExp(`<RichTextField[\\s\\S]{0,200}?name="${name}"`));
  }
  for (const name of ["suggestionsForToolImprovement", "remarks"]) {
    expect(TOOL_FORM).toMatch(new RegExp(`<RichTextField[\\s\\S]{0,200}?name="${name}"`));
  }

  // Multi-line but not narrative — microphone, no formatting.
  expect(ARTISAN_FORM, "an address is spoken, never bolded").toMatch(/<DictatedTextArea[\s\S]{0,200}?name="address"/);
  expect(PROCESS_FORM, "the step-notes rows get a mic each").toMatch(/<OnDeviceDictationButton/);

  // Deliberately untouched. Each of these is recorded in the report with its reason; the assertions
  // are here so that "somebody adds a toolbar to the Aadhaar box" fails a test rather than a review.
  expect(ARTISAN_FORM, "dos/donts stay the numbered-list control").toMatch(/<DosDontsField/);
  expect(ARTISAN_FORM, "no editor on an identity number").not.toMatch(/<RichTextField[\s\S]{0,200}?name="aadhaarNumber"/);
  /*
    NARROWED TO THE STEP-NOTES ROWS, WHICH IS WHAT THE SENTENCE WAS EVER ABOUT.

    This read `not.toMatch(/<RichTextField/)` over the WHOLE of ProcessForm, and it began failing
    when another lane gave the form a PROCESS-level "What happens in this process" editor. The
    invariant it names is untouched by that: `ProcessStep.notes` is a different column from
    `Process.notes`, the several per-step rows are joined with a blank line into the one step column,
    and Android’s `MultiNoteInput` splits that column back apart on blank lines — a document in
    THERE would be one note containing JSON. A narrative box on the process itself is exactly the
    "formatting on the LARGER boxes only" rule the rest of this sweep applies, so the assertion was
    over-broad rather than the code wrong.

    Asserting on the control instead of on the absence of a string is what makes it stay true: the
    step rows render `MultiNoteInput`, whose own definition is checked below for the same thing.
  */
  const stepNotes = PROCESS_FORM.slice(PROCESS_FORM.indexOf("{step.recordAdditional ? ("));
  expect(stepNotes.slice(0, 400), "the step-notes rows are the plain multi-note control").toContain(
    "<MultiNoteInput"
  );
  expect(stepNotes.slice(0, 400), "step notes must NOT become a document — Android splits that column").not.toMatch(
    /<RichTextField/
  );
  const multiNote = PROCESS_FORM.slice(PROCESS_FORM.indexOf("function MultiNoteInput"), PROCESS_FORM.indexOf("// The process form (create + edit)"));
  expect(multiNote, "and no editor inside the control itself either").not.toMatch(/<RichTextField/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. The second sweep: every remaining record page, and the boxes that stay bare
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A NAME IS NOT DICTATED AND A PHRASE IS — the one rule this whole section is made of.
 *
 * A recogniser returns the nearest DICTIONARY word for a token that is not one, so a proper noun, an
 * identifier and a number each come back wrong in a way the speaker does not notice until much
 * later. Every page below therefore splits its boxes the same way, and every split is asserted in
 * BOTH directions: the prose box has a control, the identifier box does not. Only the second
 * direction catches "somebody dictated the section code because it looked like a text box".
 */
test("the questionnaire family dictates its prose and leaves its identifiers alone", () => {
  // /questionnaires — the designer's own list, and the create form on it.
  expect(Q_LIST, "a new questionnaire's title is a phrase somebody composes").toMatch(
    /<DictatedTextInput[\s\S]{0,200}?name="title"/
  );
  expect(Q_LIST, "and so is its description").toMatch(/<DictatedTextInput[\s\S]{0,200}?name="description"/);

  // /questionnaires/[id] — the authoring surface.
  for (const name of ["title", "prompt", "helpText"]) {
    expect(Q_DETAIL, `${name} is prose and must carry a microphone`).toMatch(
      new RegExp(`<DictatedTextInput[\\s\\S]{0,300}?name="${name}"`)
    );
  }
  expect(Q_DETAIL, "the questionnaire description takes the multi-line box").toMatch(
    /<DictatedTextArea[\s\S]{0,200}?name="description"/
  );
  expect(Q_DETAIL, "a SECTION CODE is an identifier, not a phrase").not.toMatch(
    /<DictatedTextInput[\s\S]{0,300}?name="code"/
  );

  // /questionnaires/[id]/answer — the sitting itself.
  expect(Q_ANSWER, "the sitting's notes are dictated").toMatch(/<DictatedTextArea[\s\S]{0,200}?name="notes"/);
  expect(Q_ANSWER, "and every answer box has its own button").toMatch(/<OnDeviceDictationButton/);
  for (const name of ["respondentName", "title"]) {
    expect(Q_ANSWER, `${name} is a proper noun; the page says so at the box`).not.toMatch(
      new RegExp(`<DictatedTextInput[\\s\\S]{0,300}?name="${name}"`)
    );
  }

  // /questionnaire — the ministry instrument.
  for (const name of ["title", "place"]) {
    expect(Q_SINGULAR, `the interview ${name} was named in the requirement`).toMatch(
      new RegExp(`<DictatedTextInput[\\s\\S]{0,300}?name="${name}"`)
    );
  }
  expect(Q_SINGULAR, "a new question prompt is prose").toMatch(/<DictatedTextArea[\s\S]{0,300}?name="prompt"/);
  expect(Q_SINGULAR, "the admin editor's section title is seeded, then dictated").toContain(
    "<SeededSectionTitle"
  );
  /*
    THE SECTION CODE ON THIS PAGE IS CONTROLLED AND STILL BARE, so the assertion names the CONTROL
    around `newCode` rather than a `name=` this box does not carry. Reading the whole file for "no
    DictatedTextInput near code" would pass for the wrong reason the day somebody renames the state.
  */
  expect(Q_SINGULAR, "the section code stays a plain box").toMatch(/<TextInput value=\{newCode\}/);
});

/**
 * `formElement.reset()` IS THE TRAP ON THE MEDIA FORM, AND IT IS INVISIBLE IN A DIFF.
 *
 * `reset()` rewrites the DOM node and tells React nothing, so any box whose value React is holding
 * is re-painted with the PREVIOUS upload's text on the very next render — the researcher's second
 * photograph arrives carrying the first one's caption. Both dictated boxes on that form hold their
 * value in React (one in the page, one inside `DictatedTextArea`), so both need clearing by hand,
 * and the two mechanisms differ only because the two components have different contracts.
 *
 * Asserted because nothing else would catch it: the form still submits, the upload still succeeds,
 * and the wrong caption is a real caption on a real file.
 */
test("the media form clears both dictated boxes when it resets itself", () => {
  expect(MEDIA_PAGE, "the object name is dictated — Android's box has been since the default flipped").toMatch(
    /<DictatedTextInput[\s\S]{0,300}?name="mediaTitle"/
  );
  expect(MEDIA_PAGE, "the caption is the multi-line dictated box").toMatch(
    /<DictatedTextArea[\s\S]{0,200}?name="caption"/
  );
  expect(MEDIA_PAGE, "the caption remounts on reset, which is how a self-controlled box re-seeds").toMatch(
    /<DictatedTextArea key=\{resetNonce\}/
  );
  const reset = MEDIA_PAGE.slice(MEDIA_PAGE.indexOf("formElement.reset();"));
  expect(reset.slice(0, 400), "the page-held title must be cleared in the same block").toContain(
    'setMediaTitle("")'
  );
  expect(reset.slice(0, 400), "and the caption's key must be bumped there too").toContain("setResetNonce");
});

/**
 * The Workshop record form, which was the last web record page still typing into bare boxes.
 *
 * Its three prose columns are CONTROLLED for the reason the file states beside them, and `setDirty`
 * is armed by hand at each: a dictated phrase is a React state write and fires no native `input`
 * event, so the form's own `onInput` guard cannot see it. A box that dictates without arming the
 * guard loses its text to an unsaved-changes dialog it never triggered.
 */
test("the workshop record form dictates its three prose columns and arms the dirty guard", () => {
  /*
    SLICED PER ELEMENT RATHER THAN MATCHED WITH A PROXIMITY WINDOW.

    A window is a guess about how much prose sits between the `name` and the call, and the guess is
    wrong the moment somebody writes a comment inside the element — which is what happened here on
    the first attempt: `name="title"` and its `setDirty(true)` are more than 300 characters apart
    because the REASON for that call is written between them, and the reason is worth more than the
    assertion's convenience. Slicing to the element's own closing bracket bounds the read exactly,
    and it keeps being right however long the comment grows.
  */
  for (const name of ["title", "place", "description"]) {
    const at = WORKSHOP_PAGE.indexOf(`name="${name}"`);
    expect(at, `${name} is not on this form at all`).toBeGreaterThan(-1);
    const opens = WORKSHOP_PAGE.lastIndexOf("<Dictated", at);
    expect(opens, `${name} must be on a DictatedTextInput or a DictatedTextArea`).toBeGreaterThan(-1);
    const element = WORKSHOP_PAGE.slice(opens, WORKSHOP_PAGE.indexOf("/>", at));
    expect(element, `${name} must arm the dirty guard — a dictated phrase fires no native input event`).toContain(
      "setDirty(true)"
    );
  }
});

/*
 * NOT PROVEN HERE, and worth naming rather than leaving as a gap somebody rediscovers:
 *
 *  - that a browser PAINTS the microphone on `/artisans/new`, that pressing it opens the recogniser,
 *    and that a dictated phrase lands at the caret. That needs the signed-in dev server, the pattern
 *    `rich-text-editor.spec.ts` uses, plus an `addInitScript` stub of `window.SpeechRecognition`
 *    (headless Chromium ships no real one).
 *  - that the backend flattens a stored document on the way out. It does not yet, which is why
 *    `encodeStoredRichText` writes prose for everything except a deliberately formatted field — see
 *    the header of `components/richtext/storedRichText.ts` for the bounded cost that leaves.
 */
