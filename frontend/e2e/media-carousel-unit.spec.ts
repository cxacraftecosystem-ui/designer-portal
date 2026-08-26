import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test } from "@playwright/test";

import { describeSubject } from "../components/media/MediaCarousel";

/**
 * The carousel's accessible naming, pinned — because nothing pinned it and four strings shipped wrong.
 *
 * ── WHY THIS FILE EXISTS ────────────────────────────────────────────────────────────────────────
 *
 * `MediaCarousel` was added on 2026-08-25 and `grep` for it across `frontend/e2e`, `backend/tests`
 * and `android/app/src/test` returned ZERO hits. In that gap it shipped a stutter on every one of its
 * accessible strings: the region announced "traditional motif photographs photographs", the empty
 * state read "No traditional motif photographs photographs yet.", and each arrow said "Previous
 * traditional motif photographs photograph" — the stutter once on arrival and then again on every
 * step. It was found by a reviewer reading the code, not by anything that runs.
 *
 * The cause is worth stating because it is the reason a spec is the right answer here rather than a
 * more careful call site: the component's `noun` prop asked for a bare subject ("traditional motif")
 * and BOTH of the repository's call sites passed the field LABEL instead — `FieldInput` with
 * `field.label.toLowerCase()`, the Android twin with `field.label.lowercase()`. Two independent
 * authors made the identical mistake in the same words, which is the signature of a contract nobody
 * can be expected to keep. `describeSubject` now accepts either shape; this is what holds it to that.
 *
 * ── WHY A PURE-FUNCTION SPEC AND NOT A BROWSER TEST ─────────────────────────────────────────────
 *
 * There is no React renderer in `devDependencies` (`@playwright/test` only), so a judgement written
 * inside JSX is only ever exercised by somebody looking at a screen — which is exactly how these four
 * strings survived. The same split, and the same reason, as `components/ui/selectFilter.ts` and
 * `components/data/cappedList.ts`: the rule is pure, so it is exported and called directly.
 */

test.describe("the carousel names its subject once", () => {
  /*
    THE TWO SHAPES MUST CONVERGE. This is the whole contract: the component is handed a label today
    and a bare noun is what its own documentation asked for, so both have to produce the same
    sentences. A fix that only handled the two labels currently in the registry would be brittle in
    exactly the way the original was.
  */
  test("a label and a bare noun produce the same strings", () => {
    const fromLabel = describeSubject("traditional motif photographs");
    const fromNoun = describeSubject("traditional motif");
    expect(fromLabel).toEqual(fromNoun);
    expect(fromLabel.many).toBe("traditional motif photographs");
    expect(fromLabel.one).toBe("traditional motif photograph");
  });

  test("the singular is what an arrow announces, and it moves by one", () => {
    // "Previous {one} " — a step moves by one picture, so a plural here describes the wrong act.
    expect(describeSubject("contemporary motif photographs").one).toBe("contemporary motif photograph");
    expect(describeSubject("cluster photograph").one).toBe("cluster photograph");
  });

  test("a stem that is only the picture word degrades to the bare word, with no doubled space", () => {
    /*
      NOT DEFENSIVENESS — three IMAGE_LIST fields in the registry are labelled exactly
      "Photographs" (`productPhotos`, `responsePhotos`, `logPhotos`), and any of them reaches this
      component the day it declares a cap. An empty stem also used to print "No  photographs yet."
      with two spaces, which is why the whitespace is collapsed rather than only trimmed.
    */
    for (const input of ["Photographs", "photographs", "photograph", "", "   "]) {
      const subject = describeSubject(input);
      expect(subject.many, `many for ${JSON.stringify(input)}`).toBe("photographs");
      expect(subject.one, `one for ${JSON.stringify(input)}`).toBe("photograph");
      expect(subject.many, "no doubled space").not.toContain("  ");
    }
  });

  test("interior whitespace is collapsed, not merely trimmed", () => {
    expect(describeSubject("  traditional   motif  photographs  ").many).toBe("traditional motif photographs");
  });

  test("a word that merely ENDS in the picture word is not eaten", () => {
    /*
      The regex is `(?:^|\s)photographs?$` and not a bare suffix test, so "microphotographs" is not
      stripped to "micro" — a word taken out of somebody's label to fix a stutter that was never
      there. The disclosed cost is that such a label doubles instead, and that is the better failure:
      it reads oddly, where the other silently rewrites a term the registry chose.
    */
    expect(describeSubject("microphotographs").many).toBe("microphotographs photographs");
  });

  test("a label ending in anything else keeps all of it", () => {
    // Real registry labels: `turntablePhotos` and `moodBoard`. Neither is capped today, so neither
    // reaches a carousel yet — which is precisely why the behaviour needs pinning rather than
    // observing.
    expect(describeSubject("360° capture").one).toBe("360° capture photograph");
    expect(describeSubject("mood & reference board").many).toBe("mood & reference board photographs");
  });

  test("case is never rewritten", () => {
    /*
      `FieldInput` lowercases the label so the two sentences read as sentences. This function must not
      also re-case: the lowering that turns "Traditional" into "traditional" would flatten a proper
      noun in a label the registry has not written yet, and case is not something a screen reader
      pronounces anyway.
    */
    expect(describeSubject("Traditional motif photographs").many).toBe("Traditional motif photographs");
  });

  test("only 'photograph(s)' is recognised — no synonym guessing", () => {
    // A stripper guessing at synonyms eventually eats a real word, and no registry label uses these.
    expect(describeSubject("reference images").many).toBe("reference images photographs");
    expect(describeSubject("site photos").many).toBe("site photos photographs");
  });
});

test("the Android twin is named by path, so the cross-platform note cannot rot", () => {
  /*
    THE PORT LANDED. `dwDescribeSubject` is on the handset as of 2026-08-26 (DwMediaCarousel.kt:178,
    with the arrows and the frame reading off it), and the paragraph this test reads says so. Until
    that day this test was named "the Android twin's outstanding port is named where somebody will
    find it" and its comment said the handset had not adopted the rule — a green test telling the
    next reader to redo finished work, which is the same failure the paragraph it guards was itself
    corrected for.

    What it pins now is the LINK, not the state: a note about a platform difference is worth nothing
    if the file it names has been moved or renamed out from under it, and neither `tsc` nor Gradle
    can see across that boundary.

    PINNED AS A PATH, NOT AS PROSE. The wording may be improved; the file it points at is the part
    that must not rot, and it is checked against the filesystem rather than against a string.
  */
  const source = readFileSync(join(__dirname, "..", "components", "media", "MediaCarousel.tsx"), "utf8");
  const twin = "android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwMediaCarousel.kt";
  expect(source, "the cross-platform note must name the file it is about").toContain(twin);
  expect(
    readFileSync(join(__dirname, "..", "..", twin), "utf8"),
    "the note points at a file that must still exist"
  ).toContain("DwMediaCarousel");
});
