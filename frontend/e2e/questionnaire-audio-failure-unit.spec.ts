import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * THE SAVE THAT DESTROYED THE RECORDING IT HAD JUST FAILED TO UPLOAD.
 *
 * The questionnaire screen is the one media surface in this repository whose files usually have no
 * copy on disk: `startRecording` builds each clip as a `File` out of the MediaRecorder's blobs and
 * puts it straight into `questionAudioFiles`. Nothing else holds those bytes.
 *
 * `submit` destructured only the `uploaded` half of every `uploadMediaBatch` result — the word
 * `failed` did not appear in the handler at all — and then ran `setMediaFiles([])` and
 * `setQuestionAudioFiles({})` unconditionally. A batch in which one take of one question was refused
 * therefore cleared every take of every question, with no error and nothing left on screen to say a
 * recording had ever existed. It needed only a mixed result, which is the ordinary shape of a
 * multi-take batch on a field connection.
 *
 * WHAT THE HANDLER READS NOW IS `BatchResult.outcomes`: the same guard with the second array taken
 * out of it. One entry per file we handed over, at its position, carrying the `File` itself — so the
 * `uploadedByIndex`-against-`files` zip these assertions used to pin is gone, and with it the class
 * of arithmetic that misfiled identity photographs twice elsewhere in this repository. The
 * assertions below moved onto the new spelling; the property they pin is the one it always was.
 *
 * Every sibling surface already guards this — `app/(protected)/crafts/page.tsx`,
 * `app/(protected)/workshops/page.tsx`, `components/forms/ArtisanForm.tsx`, `ProductForm`,
 * `ToolForm` — each by reading the failures, setting an error and returning BEFORE its reset. The
 * two differences here are forced by this screen, not invented for it: the failures must be kept
 * PER QUESTION KEY, and only the failures may be kept, because `uploadMediaBatch` consumes the
 * staged object of every file it is handed (`takeStagedFor`), so putting the whole batch back would
 * upload and link a second copy of every clip that already landed.
 *
 * ── WHAT THE FIRST ATTEMPT AT THAT GUARD GOT WRONG, AND WHY THESE TESTS PIN IT ────────────────
 * The guard was written to set `error`, then refresh the interviews table. `error` is the LIST's
 * message: the effect on `[page, ...]` re-runs `loadInterviews`, which ends in `setError(null)` on
 * success and its own sentence on failure — so on the `page !== 1` branch the warning was gone one
 * round trip after it appeared, leaving a form silently repopulated with the only copy of the audio
 * and nothing on screen saying the clips had not been sent. That is the very outcome the guard
 * exists to prevent, so the warning now has its own state (`uploadError`) that the list loader never
 * touches, and it is written BEFORE the refresh, which is not awaited.
 *
 * It also wrote state back with values captured before the awaits (`setMediaFiles(kept)`), and
 * nothing on this form disables the record buttons while a save is in flight — so a clip recorded
 * during the upload window was overwritten by the very guard that promised to keep it. The guard
 * now PRUNES what landed out of current state with functional updaters instead.
 *
 * WHY THIS IS A SOURCE READ. The handler is defined inline in the page component and this
 * repository has no React renderer in its devDependencies — Playwright is the whole of it — so
 * mounting the page is not available. `questionnaire-workshop-filter-unit.spec.ts` and
 * `derived-fields-unit.spec.ts` read their subjects the same way and for the same reason. These
 * assertions pin the SHAPE of the guard; they do not prove a browser paints the banner.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const PAGE = ["app", "(protected)", "questionnaire", "page.tsx"];

/** The text between two markers, so an assertion cannot drift into a neighbouring function. */
function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

/**
 * The interview handler alone — the page also holds a question editor and a completion matrix.
 *
 * Sliced from `const saved = outcome.saved` deliberately: the OFFLINE branch above it clears the
 * same two pieces of state and is right to, because `saveOrQueue` has written every file into the
 * outbox before it returns. Only the online path below it destroys anything.
 *
 * The end marker is the handler's own catch MESSAGE, not `} catch (err) {`: the two per-batch
 * catches inside the pass now name their error too, and the shorter marker would have cut the slice
 * off before the guard it exists to inspect.
 */
function afterSave(): string {
  const submit = between(
    read(...PAGE),
    "async function submit(event: React.FormEvent<HTMLFormElement>)",
    "async function remove("
  );
  return between(submit, "const saved = outcome.saved;", '"Unable to save interview"');
}

test("a clip that did not upload is kept, and the ones that did are pruned out of the form", () => {
  const submit = afterSave();

  /*
    Both batches — the whole-interview audio and each question's clips — must read the PER-FILE
    result rather than only `uploaded`.

    THE SHAPE THIS PINS CHANGED, AND THE PROPERTY DID NOT. It used to be `uploadedByIndex[index]`
    zipped against the page's own `mediaFiles`/`files` array: correct, but two arrays kept the same
    length by agreement, which is exactly the arithmetic that misfiled identity photographs twice in
    `lib/media.ts`'s own history. `BatchResult.outcomes` carries the `File` INSIDE the entry, so the
    handler now walks one array and there is nothing left to line up. Asserted on `result.outcomes`
    for the same reason the old assertion named `uploadedByIndex`: reading only `uploaded` is the
    defect this whole file exists for, and it must stay visible in the source.
  */
  const perFile = submit.split("result.outcomes").length - 1;
  expect(perFile, "both uploadMediaBatch calls must read the per-file result, not just `uploaded`").toBeGreaterThanOrEqual(4);
  // The FILE, never the name: two takes of one question carry the same generated file name, so a
  // name match would put back the wrong take. See BatchResult in lib/media.ts.
  expect(submit).toContain("if (outcome.media) landedInterviewAudio.add(outcome.file);");
  expect(submit).toContain("failedNames.push(outcome.file.name);");
  expect(submit, "the failures must not be matched by file name").not.toContain("failed.map");

  // What landed is what leaves the form; nothing else is touched. A whole-list write-back would
  // delete a clip recorded while the upload was in flight (the record buttons stay live).
  const guard = between(submit, "if (failedNames.length) {", "setSaving(false);");
  expect(guard, "the kept interview audio must be computed from CURRENT state").toContain(
    "setMediaFiles((current) => current.filter((file) => !landedInterviewAudio.has(file)));"
  );
  expect(guard, "the kept question clips must be computed from CURRENT state").toContain(
    "setQuestionAudioFiles((current) => {"
  );
  expect(guard, "a captured-value write-back would destroy a clip recorded during the upload").not.toMatch(
    /setQuestionAudioFiles\(kept/
  );
});

test("the failure branch returns before the reset that clears the recordings", () => {
  const submit = afterSave();

  const guard = submit.indexOf("if (failedNames.length) {");
  expect(guard, "there is no failure branch at all").toBeGreaterThan(-1);

  // The reset the defect was: these two lines destroy recorder-produced files, so every one of them
  // must sit AFTER the branch that returns.
  const clearQuestionAudio = submit.indexOf("setQuestionAudioFiles({});", guard);
  expect(clearQuestionAudio, "the reset must come after the guard, never before it").toBeGreaterThan(guard);
  expect(submit.indexOf("setQuestionAudioFiles({});"), "an earlier reset would still destroy the clips").toBe(
    clearQuestionAudio
  );

  const branch = submit.slice(guard, clearQuestionAudio);
  expect(branch, "the guard has to stop the handler, not merely warn").toContain("return;");
  expect(branch).toContain("failed to upload");
});

test("the warning survives the table refresh that follows it", () => {
  const source = read(...PAGE);
  const guard = between(afterSave(), "if (failedNames.length) {", "return;");

  // `error` is the LIST's message and `loadInterviews` overwrites it on every fetch. This warning
  // must not be written there.
  expect(guard, "the upload warning must not be written into the list's `error`").not.toContain("setError(");
  expect(guard).toContain("setUploadError(");

  // …and the refresh it triggers must come after the message, and must not be awaited: this branch
  // fires on a dead connection, so awaiting it would hold both the message and the button hostage
  // to the whole timeout.
  const message = guard.indexOf("setUploadError(");
  const refresh = guard.indexOf("if (page !== 1) setPage(1);");
  expect(refresh, "the guard should still refresh the table — the interview really was saved").toBeGreaterThan(-1);
  expect(refresh, "the refresh must be triggered after the warning is on screen").toBeGreaterThan(message);
  expect(guard, "an awaited reload blocks the warning behind a timeout").not.toContain("await loadInterviews();");
  expect(guard).toContain("else void loadInterviews();");

  // The list loader owns `error` and only `error`; if it ever clears this one the warning is lost.
  const loader = between(source, "async function loadInterviews()", "useEffect(");
  expect(loader, "loadInterviews must never clear the upload warning").not.toContain("setUploadError");

  // Cleared when the researcher presses Save again — that press IS the retry it asks for.
  const submitHead = between(source, "async function submit(event: React.FormEvent<HTMLFormElement>)", "const saved = outcome.saved;");
  expect(submitHead).toContain("setUploadError(null);");
});

test("the warning names the cause and the retry that actually exists", () => {
  const submit = afterSave();

  // A list of file names cannot tell "you are offline" from "the server refused this file type".
  // MediaBatchError's message carries that sentence and `failed[].error` carries it per file.
  expect(submit, "the reason a batch was refused must not be swallowed by a bare catch").not.toContain("} catch {");
  expect(submit).toContain("firstCause ??= batchCause(err);");
  // Read off the OUTCOME that failed, not off `failed[0]` — same first reason in the caller's own
  // order, taken from the entry that already carries the file it belongs to.
  expect(submit).toContain("firstCause ??= outcome.failure?.error ?? null;");
  /*
    `batchCause` takes the underlying reason, NOT MediaBatchError's own message: that message's
    second clause is advice for a record with an edit screen ("re-open it and re-attach the media"),
    which no interview row offers.

    THAT CLAUSE IS NO LONGER ONE SENTENCE. It used to be the constant "Check your internet connection
    and try again — …" whatever had happened, so a 413 or a 415 sent a researcher out to look for
    signal they already had; `adviceForALostBatch` in `lib/media.ts` now picks it from the triage
    verdict. This helper is unaffected either way, which is the point of asserting the reason it
    reads rather than the words it avoids.
  */
  const helper = between(read(...PAGE), "function batchCause(err: unknown): string {", "export default function QuestionnairePage()");
  expect(helper).toContain("err instanceof MediaBatchError");
  expect(helper).toContain("err.failures[0]?.error");

  const guard = between(submit, "if (failedNames.length) {", "return;");
  expect(guard).toContain("firstCause ? ` Reason given: ${firstCause}`");
  // `create_interview` folds a repeat submission into the interview that already exists for the same
  // artisan set (`artisanSetKey`, backend/app/api/routes/questionnaire.py), so pressing Save again
  // sends the kept clips to the SAME entry. The banner used to deny that retry existed.
  expect(guard, "the banner must point at the retry the server actually supports").toContain("Press Save again");
  // The banner does not quote a backend path at a field researcher, but the guard has to cite the
  // fold it relies on so the next reader can check that the retry really is idempotent.
  expect(guard, "the guard must cite the server-side fold its promise depends on").toContain(
    "artisan_set_key(payload.artisanIds)"
  );
  expect(guard).toContain("backend/app/api/routes/questionnaire.py");
});

test("one refused question does not abort the questions after it", () => {
  const submit = afterSave();
  // `uploadMediaBatch` THROWS when a batch landed nothing (MediaBatchError), so an unguarded call
  // inside the loop would send the handler to its catch and leave the later sections unattempted.
  const loop = between(submit, "for (const [key, files] of Object.entries(questionAudioFiles))", "setQuestionProgress({});");

  const tryIndex = loop.indexOf("try {");
  const callIndex = loop.indexOf("await uploadMediaBatch({");
  expect(tryIndex, "the per-question upload must be wrapped").toBeGreaterThan(-1);
  expect(callIndex).toBeGreaterThan(tryIndex);
  // Nothing but the upload may sit inside the try: a throw out of the tray bookkeeping is not an
  // upload failure, and treating it as one would re-upload files that already landed.
  const tryBody = loop.slice(tryIndex, loop.indexOf("} catch (err) {", tryIndex));
  expect(tryBody, "only the upload belongs inside the try").not.toContain("addCompleted(");
  // A batch that landed nothing leaves `result` undefined, so every one of its clips is a failure
  // and none of them is marked as landed — which is what keeps them on the form.
  expect(loop).toContain("failedNames.push(...files.map((file) => file.name));");

  // A clip whose question was deleted from the editor on this same page cannot be captioned, so it
  // cannot be uploaded — but it stays on the form, and the banner accounts for it rather than
  // passing over it while claiming everything left there is named.
  expect(loop).toContain("orphanedClips += files.length;");
  expect(between(submit, "if (failedNames.length) {", "return;")).toContain("orphanedClips");
});
