/**
 * The questionnaire voice note: what it is allowed to send, what bounds it, and where the words go.
 *
 * ── WHY THESE ARE THE THINGS PINNED ────────────────────────────────────────────────────────────
 * On 2026-08-31 `/questionnaire` (SINGULAR — the ministry instrument, never `/questionnaires`, the
 * designer-owned form builder) began posting a just-recorded clip to the dictation API for an
 * immediate transcript. That crosses a line nothing else on this page crosses: a named artisan's
 * recorded voice leaves the device for ElevenLabs, Deepgram or OpenAI, synchronously, while she is
 * sitting there. Three properties keep that honest and none of them is visible in a diff:
 *
 *   1. **A workshop id, or no request at all.** `POST /design-workshops/{id}/dictate` is the only
 *      route in this application that sends audio under a consent gate, and the gate is the
 *      workshop's `dictationConsent` column. Reaching for the id-less route — which still exists and
 *      answers 410 — or inventing a fallback id would be a send with nobody's consent behind it.
 *   2. **A ceiling on the recording.** `startRecording` used to run until Stop, and the route 413s
 *      over six megabytes. Uploading first and learning that afterwards is a failure paid for on a
 *      village connection.
 *   3. **The closure is not read.** The handler that fires the upload was built when Record was
 *      pressed, so `answers` and the workshop id in scope there are minutes stale. Reading them
 *      would overwrite words typed during the recording — the exact overwrite the owner's "offer it,
 *      do not impose it" rule forbids, arriving through a closure instead of through the rule.
 *
 * Everything here is a source assertion or a pure function, for this repository's usual reason:
 * there is no React renderer in devDependencies, so a judgement inside JSX is only ever exercised by
 * somebody looking at a screen.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { safeDocumentFileName } from "@/components/richtext/MarkdownDocument";
import { plainFromStoredRichText } from "@/components/richtext/storedRichText";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const Q_SINGULAR = read("app", "(protected)", "questionnaire", "page.tsx");
const CONSOLIDATED = read("app", "(protected)", "questionnaire", "consolidated", "[artisanId]", "page.tsx");
const TRANSCRIPT_BLOCK = read("components", "media", "TranscriptBlock.tsx");
const MARKDOWN_DOCUMENT = read("components", "richtext", "MarkdownDocument.tsx");

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The consent story, as the code enforces it
 * ──────────────────────────────────────────────────────────────────────────── */

test("the clip is posted through the workshop route, which is the only gated one", () => {
  expect(Q_SINGULAR, "the page calls the server dictation helper").toContain("dictateAudio(");
  // `dictateAudio`'s third argument is a REQUIRED workshop id precisely so that a call site cannot
  // forget it — the id-less path enforces no consent. Naming the retired route here would be a
  // regression to a 410, or worse, to an ungated send if it were ever revived.
  expect(Q_SINGULAR, "the retired id-less route is not named").not.toContain("DW_DICTATE_PATH");
});

test("no workshop named means NO REQUEST, not a request with a guessed id", () => {
  // The guard and the sentence, together: a page that simply returned would look like a recorder
  // that ate the take.
  expect(Q_SINGULAR).toMatch(/if\s*\(!workshopId\)\s*\{[\s\S]{0,400}?return;/);
  expect(Q_SINGULAR, "and it says why in one line").toContain(
    "Instant transcript needs a design workshop named above."
  );
});

test("the refusal says the clip is still saved, because it is", () => {
  // The queue path is untouched: the clip uploads with the interview and is transcribed later. A
  // refusal that did not say so would read as the recording having been thrown away.
  const refusals = Q_SINGULAR.match(/The clip is saved and transcribed later\./g) ?? [];
  expect(refusals.length, "both refusals carry it — no workshop, and too long").toBeGreaterThanOrEqual(2);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The ceiling
 * ──────────────────────────────────────────────────────────────────────────── */

test("a questionnaire recording is bounded in time and in bytes", () => {
  expect(Q_SINGULAR, "a duration ceiling exists").toContain("const CLIP_MAX_MS");
  expect(Q_SINGULAR, "and the recorder is stopped by it").toMatch(/clipCapTimerRef[\s\S]{0,600}?recorder\.stop\(\)/);
  expect(Q_SINGULAR, "the encoder rate is pinned, or the duration says nothing about size").toContain(
    "audioBitsPerSecond: CLIP_BITS_PER_SECOND"
  );
  expect(Q_SINGULAR, "and the ACTUAL blob is checked against the route's own limit").toMatch(
    /file\.size > DICTATE_MAX_BYTES/
  );
});

test("the duration ceiling really does fit inside the byte ceiling at the pinned rate", () => {
  // Read off the source rather than restated, so the day somebody raises one number the other has
  // to still hold. This is the whole argument for pinning the bitrate: without it the product of
  // these two constants is unknowable.
  const maxMs = Number(/const CLIP_MAX_MS = (\d+) \* (\d+) \* (\d+);/.exec(Q_SINGULAR)?.slice(1).reduce((a, b) => String(Number(a) * Number(b))) ?? 0);
  const bits = Number(/const CLIP_BITS_PER_SECOND = (\d+);/.exec(Q_SINGULAR)?.[1] ?? 0);
  const maxBytes = Number(/const DICTATE_MAX_BYTES = (\d+) \* 1024 \* 1024;/.exec(Q_SINGULAR)?.[1] ?? 0) * 1024 * 1024;
  expect(maxMs, "the duration cap parsed").toBeGreaterThan(0);
  expect(bits, "the bitrate parsed").toBeGreaterThan(0);
  expect(maxBytes, "the byte cap parsed").toBeGreaterThan(0);
  expect((maxMs / 1000) * (bits / 8), "a full-length take still fits the dictation route").toBeLessThan(maxBytes);
});

test("stopping at the ceiling is announced, never silent", () => {
  expect(Q_SINGULAR).toContain("Recording stopped at");
  expect(Q_SINGULAR, "and the take is kept").toContain("The take is kept");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The stale closure
 * ──────────────────────────────────────────────────────────────────────────── */

test("the transcript path reads the live answers and the live workshop, never its closure", () => {
  expect(Q_SINGULAR, "the box is compared live").toContain("liveRef.current.answers[key]");
  expect(Q_SINGULAR, "the machine's copy is read live").toContain("liveRef.current.machineText[key]");
  expect(Q_SINGULAR, "and so is the workshop the picker may have gained since Record").toContain(
    "const workshopId = liveRef.current.workshopId"
  );
});

test("the ref is written in an effect, not during render", () => {
  // A render can be discarded under concurrent rendering, and a ref written on a discarded render
  // leaves a reader holding state that never committed — `useLeaveGuard` records what that costs.
  expect(Q_SINGULAR).toMatch(/useEffect\(\(\) => \{\s*liveRef\.current = \{/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. Where the words land, and the flag on them
 * ──────────────────────────────────────────────────────────────────────────── */

test("the answer is a rich text box named by the question already printed above it", () => {
  expect(Q_SINGULAR).toMatch(/<RichTextField[\s\S]{0,1200}?labelledBy=\{`question-label-\$\{question\.id\}`\}/);
});

test("a transcript never silently replaces an edited answer", () => {
  // The offer, not the overwrite. `offeredTranscript` is the held-back text and the two buttons are
  // the only ways out of it.
  expect(Q_SINGULAR).toContain("setOfferedTranscript");
  expect(Q_SINGULAR, "the researcher is asked").toContain("Your words are kept either way.");
  expect(Q_SINGULAR).toContain("Add to answer");
  expect(Q_SINGULAR).toContain("Discard");
});

test("NEITHER branch can lose a syllable — both paths append through the shared joiner", () => {
  // A second clip against one question is the rest of the sentence, not a better version of the
  // first, and the accept button exists precisely because the box holds words a person wrote. So
  // `appendDictatedPhrase` — the repository's one joiner, written because "a commit that replaced
  // the box would delete everything already in it at the first pause for breath" — is on both paths.
  expect(Q_SINGULAR).toMatch(/const merged = appendDictatedPhrase\(inBox, text\);/);
  expect(Q_SINGULAR).toMatch(/const merged = appendDictatedPhrase\(answers\[question\.id\] \?\? "", text\);/);
});

test("accepting an offer leaves the machine's copy alone, so the answer stays flagged as edited", () => {
  // The box then holds the researcher's words AND the machine's. Updating `machineText` there would
  // relabel a mixed answer as untouched machine output — the claim the flag exists to prevent.
  const accept = /onAccept=\{\(\) => \{[\s\S]*?\n {26}\}\}/.exec(Q_SINGULAR)?.[0] ?? "";
  expect(accept, "the accept handler was located").toContain("appendDictatedPhrase");
  expect(accept, "and it does not touch the machine's copy").not.toContain("setMachineText");
});

test("a whole-section take is never filed under one question's answer", () => {
  // `applyTranscript` returns before touching any box for a section key. A section take covers a
  // dozen questions, and choosing one of them would be the page inventing an attribution only the
  // researcher can make.
  const body = /function applyTranscript\([\s\S]*?\n {2}\}/.exec(Q_SINGULAR)?.[0] ?? "";
  expect(body, "applyTranscript was located").toContain("isSectionClipKey");
  const sectionBranch = /if \(isSectionClipKey\(key\)\) \{[\s\S]*?\n {4}\}/.exec(body)?.[0] ?? "";
  expect(sectionBranch, "the section branch was located").toContain("setMachineText");
  expect(sectionBranch, "and it writes into no answer box").not.toContain("setAnswers");
  expect(sectionBranch, "and it returns before the question path").toContain("return;");
  expect(body.indexOf("isSectionClipKey")).toBeLessThan(body.indexOf("setAnswers"));
});

test("the machine's copy is cleared between interviews, or the next one opens flagged", () => {
  expect(Q_SINGULAR).toContain("function clearQuickTranscripts()");
  // Both reset paths — the queued branch and the saved branch.
  expect((Q_SINGULAR.match(/clearQuickTranscripts\(\);/g) ?? []).length).toBe(2);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5. One component for render, copy and download
 * ──────────────────────────────────────────────────────────────────────────── */

test("every surface that shows a transcript mounts the same component", () => {
  for (const [name, source] of [
    ["/questionnaire", Q_SINGULAR],
    ["the consolidated interview", CONSOLIDATED],
    ["TranscriptBlock", TRANSCRIPT_BLOCK]
  ] as const) {
    expect(source, `${name} mounts MarkdownDocument`).toContain("<MarkdownDocument");
  }
});

test("copy and download live in that component and are not re-derived per screen", () => {
  expect(MARKDOWN_DOCUMENT).toContain("navigator.clipboard.writeText");
  expect(MARKDOWN_DOCUMENT).toContain("anchor.download");
  for (const [name, source] of [
    ["/questionnaire", Q_SINGULAR],
    ["the consolidated interview", CONSOLIDATED],
    ["TranscriptBlock", TRANSCRIPT_BLOCK]
  ] as const) {
    expect(source, `${name} does not roll its own clipboard call`).not.toContain("clipboard.writeText");
    expect(source, `${name} does not roll its own download anchor`).not.toContain("anchor.download");
  }
});

test("the markdown travels, not the rendered text", () => {
  // A refined transcript's speaker labels ARE the markdown. Flattening them produces a wall of prose
  // in which nobody can tell who spoke, which is what the refinement pass exists to establish.
  expect(MARKDOWN_DOCUMENT).toContain("navigator.clipboard.writeText(text)");
  expect(MARKDOWN_DOCUMENT).toContain("new Blob([text]");
  expect(MARKDOWN_DOCUMENT, "and it is saved as markdown").toContain("text/markdown");
});

test("the object URL is revoked on a later task, or Safari cancels the download", () => {
  expect(MARKDOWN_DOCUMENT).toMatch(/setTimeout\(\(\) => URL\.revokeObjectURL\(url\), 0\)/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 6. "Not edited" is a claim only one surface may make
 * ──────────────────────────────────────────────────────────────────────────── */

test("the two read-only surfaces pass TRUE or nothing, never false", () => {
  // The stamp is null both for a transcript nobody edited and for every row stored before the
  // column existed on 2026-08-31, and those are different facts. Only the interview form holds the
  // machine's own copy to compare against, so only it may say "Not edited".
  expect(CONSOLIDATED).toContain("edited={row.transcriptEdited ? true : undefined}");
  expect(TRANSCRIPT_BLOCK).toContain("edited={current.transcriptEditedAt ? true : undefined}");
});

test("the flag is a word and never a colour alone", () => {
  expect(MARKDOWN_DOCUMENT).toContain("Edited");
  expect(MARKDOWN_DOCUMENT).toContain("Not edited");
  // Three states: an absent answer draws nothing at all.
  expect(MARKDOWN_DOCUMENT).toMatch(/if \(edited === undefined\) return null;/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 7. A formatted answer must not reach a reader as braces
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("plainFromStoredRichText", () => {
  test("an ordinary typed answer is returned untouched", () => {
    // Nearly every answer is this. `encodeStoredRichText` writes prose for an unformatted document,
    // so the flattener has to be identity on it or the change would rewrite the whole corpus.
    expect(plainFromStoredRichText("She dyes the warp before the loom is dressed.")).toBe(
      "She dyes the warp before the loom is dressed."
    );
  });

  test("a formatted answer is flattened to its words", () => {
    const doc = JSON.stringify({ blocks: [{ kind: "paragraph", spans: [{ text: "Bagru", marks: ["bold"] }] }] });
    expect(plainFromStoredRichText(doc)).toBe("Bagru");
  });

  test("an answer that merely starts with a brace is somebody's typing, not a document", () => {
    expect(plainFromStoredRichText("{not a document}")).toBe("{not a document}");
  });

  test("an unanswered question is an empty string, so callers keep their own blank wording", () => {
    expect(plainFromStoredRichText(null)).toBe("");
    expect(plainFromStoredRichText(undefined)).toBe("");
  });
});

test("every surface that renders an answer as prose flattens it", () => {
  // The interview form mounts an editor, which decodes for itself. These three render the column
  // WITHOUT one, and a reader quoting into a ministry report must not meet `{"blocks":[{"kind":…`.
  expect((Q_SINGULAR.match(/plainFromStoredRichText\(response\.answerText\)/g) ?? []).length).toBe(2);
  expect(CONSOLIDATED).toContain("plainFromStoredRichText(row.answerText)");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 8. The download's file name — the one genuinely pure thing here
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("safeDocumentFileName", () => {
  test("strips the characters Windows refuses outright", () => {
    // Every one of these is reachable from a question prompt, which is what these names are built
    // from. A file the browser cannot write is a download button that silently does nothing.
    expect(safeDocumentFileName('a/b\\c:d*e?f"g<h>i|j', "md")).toBe("a-b-c-d-e-f-g-h-i-j.md");
  });

  test("collapses whitespace rather than leaving it in a file name", () => {
    expect(safeDocumentFileName("Section D  RAW   MATERIALS", "md")).toBe("Section-D-RAW-MATERIALS.md");
  });

  test("never leads or trails with a separator", () => {
    expect(safeDocumentFileName("  ?leading and trailing?  ", "md")).toBe("leading-and-trailing.md");
  });

  test("caps the length, because several filesystems cap a path segment at 255 BYTES", () => {
    // A UTF-8 Devanagari title reaches 255 bytes a long way before it reaches 255 characters, and a
    // questionnaire prompt runs to two thousand.
    const long = safeDocumentFileName("क".repeat(500), "md");
    expect(long.length).toBeLessThanOrEqual(63);
    expect(Buffer.byteLength(long, "utf8")).toBeLessThan(255);
  });

  test("a name that reduces to nothing still produces a usable file", () => {
    // "???" is a real prompt shape, and an empty name would make the anchor download the page.
    expect(safeDocumentFileName("???", "md")).toBe("transcript.md");
    expect(safeDocumentFileName("", "md")).toBe("transcript.md");
  });
});


/* ────────────────────────────────────────────────────────────────────────────
 * The other half of the same recording: the CLIP, and when it starts moving
 *
 * The section above is about the words — the immediate transcript that reaches the box while the
 * artisan is still in the room. This one is about the bytes. They are two acts on two clocks over
 * one file, and the second is what the report and the media queue eventually read.
 *
 * WHY IT IS PINNED HERE RATHER THAN LEFT TO READ. "The upload starts at Stop" is invisible in a
 * diff and invisible on screen — a save that took thirty seconds and a save that took two look
 * identical afterwards — so the only thing standing between this page and a silent regression to
 * upload-at-save is an assertion. An interview is the longest form in the app: a researcher works
 * down the sections for half an hour and every answer may be an audio clip, so deferring the
 * transfer means the whole sitting's audio goes up in one blocking burst at the end, on whatever
 * connection the field site has.
 *
 * THE TWO TRAPS THE HOOK'S OWN HEADER NAMES, and both are properties of THIS page rather than of
 * the hook: an eager upload starts at attach time, so discarding a clip must abort a transfer that
 * is already running; and `takeStagedFor` — which claims the staged object for a save — must run
 * synchronously before the save's first `await`, or a form that unmounts as it saves deletes the
 * object the save is about to link. The second half lives in `lib/media` and is pinned at
 * `e2e/inline-record-host-unit.spec.ts`; what this page owes is to go through `uploadMediaBatch`
 * rather than rolling its own upload, and to keep on screen exactly what did NOT land.
 * ──────────────────────────────────────────────────────────────────────────── */

test("the clip starts uploading at Stop, not at Save", () => {
  expect(Q_SINGULAR, "the recorded clips are handed to the eager-staging hook").toContain(
    "useEagerStaging(stagedQuestionAudio"
  );
});

test("every clip key is staged, the whole-section takes included", () => {
  // `questionAudioFiles` is keyed by what a clip ANSWERS: a question id, or `section:<id>` for one
  // take covering a whole section. Flattening every value is what makes the second kind eager too —
  // reading only the question ids would leave the longest recordings on the page until Save, which
  // is the exact defect this feature closes, surviving in the one case that needs it most.
  expect(Q_SINGULAR).toMatch(
    /const stagedQuestionAudio = useMemo\(\s*\(\) => Object\.values\(questionAudioFiles\)\.flat\(\)/
  );
});

test("discarding a clip removes it from the staged list, which is what aborts the transfer", () => {
  // `useEagerStaging` diffs the array it is handed and calls `discardStagedFile` on anything that
  // left it — aborting the request and deleting whatever already reached storage. So the page's
  // obligation is simply to stop holding the file; a remove that merely hid the tile would leave a
  // transfer running and an orphaned object behind it.
  expect(Q_SINGULAR).toMatch(/filter\(\(_, i\) => i !== index\)/);
});

test("the page never runs its own upload, so the staged object is claimed the one documented way", () => {
  // `uploadMediaBatch` recognises an already-staged file and claims it with `takeStagedFor`
  // synchronously, before its first await (pinned in `e2e/inline-record-host-unit.spec.ts`). A
  // second upload path on this page would be a second chance to get that ordering wrong.
  expect(Q_SINGULAR).toContain("uploadMediaBatch({");
  expect(Q_SINGULAR, "and it does not reach past it into the staging store").not.toContain(
    "takeStagedFor("
  );
  expect(Q_SINGULAR).not.toContain("stageFiles(");
});

test("only what landed leaves the form, so a claimed-and-failed clip is never re-uploaded", () => {
  // The mirror of the claim: `uploadMediaBatch` takes the staged object for the WHOLE batch, so a
  // file that landed and stayed on the form would be uploaded and linked a second time — while a
  // file that did NOT land must stay, because it is bytes that exist nowhere else, recorded once, in
  // front of an artisan who has since gone home.
  expect(Q_SINGULAR).toContain("result.outcomes");
  expect(Q_SINGULAR).toMatch(/landedQuestionAudio/);
});
