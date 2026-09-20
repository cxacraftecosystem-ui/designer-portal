import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * TAKING THE LAST DESIGNER OFF A WORKSHOP — the transition that did not exist, and now does.
 *
 * ── WHAT WAS REPORTED, AND WHAT WAS ACTUALLY WRONG ─────────────────────────────────────────────
 *
 * An officer on /officers added a designer to a workshop by mistake and could not take her off
 * again. The panel refused with *"Taking every designer off it is not something this product can
 * express"*, demanded a replacement she did not want to name, and left "Save who this workshop is
 * for" DISABLED with "1 unsaved change" printed beside it — a screen saying in one breath that
 * there is something to save and that it will not save it.
 *
 * Nothing was broken. The panel was faithfully mirroring a deliberate backend rule, and the rule's
 * stated reason was false. **"Nobody is the designer" IS an expressible state**: the product writes
 * it every time a workshop is opened without naming a designer, and design workshop `cmsxcdc2y000`
 * ("Test", IN_PROGRESS) was sitting in the live database with `designerName = None` when this was
 * measured on **2026-09-20**. What was missing was never the state — it was the TRANSITION BACK to
 * it. You could start with no designer and you could not return, which made a mistaken add a
 * one-way door. Owner's ruling, the same day: make the transition symmetric.
 *
 * ── WHAT THIS FILE PINS, AND WHAT IT DELIBERATELY DOES NOT ─────────────────────────────────────
 *
 * The server half — `designerName` to NULL, the viewer row deleted, stage 1's own `designerName`
 * field blanked and NOTHING ELSE of the ~19 prefilled fields touched, a filed report still refused
 * first — belongs to `backend/tests/`, and the singular `PUT …/designer` route keeps `designerId`
 * required because a replacement is what that door is for. This file is the PANEL's four
 * obligations and nothing beyond them:
 *
 *   1. Save is NOT disabled by an empty selection. The refusal predicate is gone from the code.
 *   2. Something real took its place: an amber notice, WORDED, naming all three consequences —
 *      no named designer, a cover page that names nobody, and the stage 1 / stage 3 profile
 *      details left exactly as they are. A signal carried only by colour is one some readers
 *      never get.
 *   3. The lead refusal fires ONLY when the resulting selection is non-empty. With nothing ticked
 *      there is no replacement to name and its sentence is simply wrong.
 *   4. The panel's intro prose no longer states the unconditional rule.
 *
 * ── WHY THIS IS A SOURCE-READING NODE SPEC ─────────────────────────────────────────────────────
 *
 * There is no React renderer in this project's devDependencies, so a decision written inside JSX is
 * a decision no test can reach — the same split, and the same reason, as
 * `design-workshop-designer-team-unit.spec.ts`, `dropdown-sweep-unit.spec.ts` and
 * `ministry-people-unit.spec.ts`.
 *
 * ⚠ **ABSENCES ARE CHECKED AGAINST COMMENT-STRIPPED SOURCE, AND THAT IS NOT A TIDINESS RULE.**
 * This repository's culture is that a rule keeps its recorded reason, so the block above
 * `emptiesTheWorkshop` NAMES the predicate it replaced and quotes the sentence that predicate
 * printed. A raw-text absence check would match that tombstone and pass while the refusal was back
 * in force — or fail while it was correctly gone. `stripComments` is what makes "this code no
 * longer does X" mean the code and not the argument about the code.
 */

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8");

/**
 * Comments out, so an absence assertion measures the CODE. Block comments first (the JSX `{/* … *\/}`
 * form collapses to a bare `{}`, which no assertion below looks at), then whole-line `//` comments.
 */
function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
}

const PAGE = read("app/(protected)/officers/page.tsx");
const PAGE_CODE = stripComments(PAGE);

/**
 * Prose with its line breaks flattened. Prettier re-wraps JSX text on every reformat, so a sentence
 * asserted as a literal substring would fail on a whitespace change that altered nothing a reader
 * sees — the kind of failure that gets a real assertion deleted.
 */
const PROSE = PAGE_CODE.replace(/\s+/g, " ");

/**
 * The `disabled={…}` expression of the panel's own Save button, located by the handler beneath it.
 * `[^{}]*` rather than `[\s\S]*?`, so the match cannot start at some earlier `disabled={` on the
 * page and swallow everything between the two — a lazy quantifier still expands as far as it must.
 */
const SAVE_DISABLED = (() => {
  const match = /disabled=\{([^{}]*)\}\s*\n\s*onClick=\{save\}/.exec(PAGE_CODE);
  expect(match, "the Save button's `disabled` expression is gone or was restructured").not.toBeNull();
  return match![1];
})();

/**
 * The early return at the top of `save()`, which must not become a second, silent disable. Anchored
 * on `!dirty` because this page carries eight other `… return;` guards, every one of them a fetch
 * generation check, and the first of them is 600 lines above this one.
 */
const SAVE_GUARD = (() => {
  const match = /if \(([^)]*!dirty[^)]*)\) return;/.exec(PAGE_CODE);
  expect(match, "`save()` no longer opens with a guard clause; this test cannot do its job").not.toBeNull();
  return match![1];
})();

/** Everything between `emptiesTheWorkshop ? (` and the close of that branch. */
const WARNING_BRANCH = (() => {
  const match = /\) : emptiesTheWorkshop \? \(([\s\S]*?)\n\s*\) : null\}/.exec(PAGE_CODE);
  expect(match, "the empty-selection notice is not in the panel's notice chain").not.toBeNull();
  return match![1].replace(/\s+/g, " ");
})();

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 1. THE REFUSAL IS GONE FROM THE CODE, AND ITS REASON IS KEPT IN THE PROSE
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("an empty selection no longer disables Save", () => {
  test("`strandsTheDesigner` is not a predicate this panel computes any more", () => {
    /*
      The whole of the reported bug in one assertion. `strandsTheDesigner` was
      `Boolean(designerName?.trim()) && selected.length === 0` and it sat in the Save button's
      `disabled`, so an officer who unticked the only designer got a live "1 unsaved change" over a
      dead button with no way forward at all.
    */
    expect(
      PAGE_CODE,
      "the empty-selection refusal is back in the code — emptying a workshop is an allowed transition"
    ).not.toContain("strandsTheDesigner");
  });

  test("the reason it was deleted is recorded, with the evidence and the date", () => {
    /*
      Not decoration. This repository deletes a rule whose recorded reason has stopped being true,
      and the reason recorded here was that "nobody is the designer" could not be expressed. The
      measurement that disproved it has to travel with the deletion, or the next reader restores the
      refusal on the strength of the same false premise.
    */
    expect(PAGE, "the measured counter-example is not cited beside the deletion").toContain("cmsxcdc2y000");
    expect(PAGE, "the measurement is undated, so a later reader cannot re-check it").toContain("2026-09-20");
    expect(PAGE, "the deleted predicate is not named, so nothing connects the comment to the change").toContain(
      "strandsTheDesigner"
    );
  });

  test("nothing else in the panel silently disables Save on an empty selection", () => {
    // Both doors, because a refusal moved from the button into the handler is the same refusal with
    // the reason taken off it: the click would do nothing and say nothing.
    expect(SAVE_DISABLED).not.toContain("strandsTheDesigner");
    expect(
      SAVE_DISABLED,
      "the warning is in the Save button's `disabled` list — a notice must not double as a refusal"
    ).not.toContain("emptiesTheWorkshop");
    expect(SAVE_GUARD).not.toContain("strandsTheDesigner");
    expect(
      SAVE_GUARD,
      "`save()` returns early on the warning, so the button is enabled and the click does nothing"
    ).not.toContain("emptiesTheWorkshop");

    // And the refusals that genuinely remain are still on both doors.
    for (const kept of ["overCap", "dropsTheLeadWithNoReplacement"]) {
      expect(SAVE_DISABLED, `\`${kept}\` no longer disables Save`).toContain(kept);
      expect(SAVE_GUARD, `\`${kept}\` no longer guards save()`).toContain(kept);
    }
  });
});

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 2. WHAT REPLACED IT IS NOT NOTHING
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("emptying a workshop is warned about before the click", () => {
  test("the panel computes the state and renders a notice in the same slot", () => {
    expect(PAGE_CODE, "no predicate answers `this save will empty the workshop`").toContain(
      "const emptiesTheWorkshop ="
    );
    // Only where something is actually being taken off: with nothing ticked and nothing saved there
    // is no consequence to warn about, and a notice standing over an inert Save is noise.
    expect(PAGE_CODE).toContain("selected.length === 0 && removed.length > 0");
    expect(WARNING_BRANCH.length, "the notice branch came back empty — the parse is wrong, not the code").toBeGreaterThan(
      120
    );
  });

  test("all three consequences are stated in words", () => {
    /*
      Non-negotiable 5: a signal that exists only as a colour is a signal some readers never get.
      The three are the ones the owner's ruling names — what happens to the workshop, what happens
      to the report, and what happens to the stage data that was already copied in. The last is the
      one a reader is most likely to fear wrongly, and silence about it reads as "and your stage 1
      is gone too".
    */
    expect(WARNING_BRANCH, "the notice does not say the workshop is left with no named designer").toContain(
      "leave this workshop with no named designer"
    );
    expect(WARNING_BRANCH, "the notice does not say the report's cover will name nobody").toContain(
      "report will name nobody on the cover"
    );
    expect(WARNING_BRANCH, "the notice does not say the stage 1 / stage 3 profile details are kept").toContain(
      "copied into stages 1 and 3"
    );
    expect(WARNING_BRANCH).toContain("stay exactly");
  });

  test("it is amber-100/500/800 and nothing else from the stock ramp", () => {
    /*
      §3.5: `amber` deep-merges with stock Tailwind, so only 100/500/800 are this repository's.
      `amber-50` and `amber-200` compile, look approximately right, and do not pair — which is how a
      warning ends up unreadable in dark mode where `amber-800` is a literal that never inverts.
    */
    expect(WARNING_BRANCH).toContain("bg-amber-100");
    expect(WARNING_BRANCH).toContain("text-amber-800");
    expect(WARNING_BRANCH).toContain("border-amber-500/30");
    expect(WARNING_BRANCH, "a stock amber rung crept into the tinted card").not.toMatch(
      /amber-(50|200|300|400|600|700|900)\b/
    );
    // Purple-700 is the only action colour on this surface, and a notice is not an action.
    expect(WARNING_BRANCH, "the notice is painting itself an action colour").not.toContain("purple-700");
  });
});

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 3. THE REFUSAL THAT SURVIVED, AND THE CONDITION THAT MAKES IT TRUE
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("dropping the lead still needs a replacement — but only when there is one to name", () => {
  test("the predicate requires a non-empty resulting selection", () => {
    /*
      The client's half of the server's `and wanted`. With a team of three, taking the lead off is
      choosing among them and the refusal is sound. With nothing ticked there is nobody to promote,
      and the sentence it prints — "name who leads it instead rather than leaving it with nobody" —
      describes an act the officer is not attempting. Unconditioned, this predicate ALONE would
      reproduce the reported bug after `strandsTheDesigner` was deleted.
    */
    const match = /const dropsTheLeadWithNoReplacement =([\s\S]*?);/.exec(PAGE_CODE);
    expect(match, "`dropsTheLeadWithNoReplacement` is gone — the sound refusal must stay in force").not.toBeNull();
    const declaration = match![1].replace(/\s+/g, " ");
    expect(
      declaration,
      "the lead refusal is not conditioned on a non-empty selection, so it fires on an empty one"
    ).toContain("selected.length > 0");
    expect(declaration).toContain("removed.includes(baselineLead)");
    expect(declaration).toContain("!leadMoved");
  });

  test("the two notices cannot both be true, so neither hides the other", () => {
    // `emptiesTheWorkshop` needs `selected.length === 0`; the refusal now needs `selected.length > 0`.
    // Asserted as a pair because the notice chain is a single `? :` ladder and only one arm renders.
    expect(PAGE_CODE).toContain("const emptiesTheWorkshop = selected.length === 0");
    expect(PAGE_CODE).toMatch(/dropsTheLeadWithNoReplacement =[\s\S]*?selected\.length > 0/);
  });
});

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 4. THE PROSE STOPPED CLAIMING THE UNCONDITIONAL RULE
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("the panel's own intro says what is now true", () => {
  test("the unconditional sentence is gone", () => {
    /*
      It read "and THAT one cannot simply be removed: choose a different lead instead" — the rule
      stated with no condition on it, which is the sentence an officer read immediately before
      meeting a dead button. It is true only while other designers remain.
    */
    expect(PROSE, "the intro still states the removal rule unconditionally").not.toContain(
      "cannot simply be removed"
    );
  });

  test("the condition and the allowed act are both spelled out", () => {
    expect(PROSE, "the intro does not say when the lead cannot be dropped").toContain(
      "While other designers stay ticked that one cannot simply be dropped"
    );
    expect(PROSE, "the intro does not say that emptying the workshop is allowed").toContain(
      "Unticking EVERYBODY is a different act and is allowed"
    );
    // And it keeps the one refusal that is not about the selection at all.
    expect(PROSE).toContain("A workshop whose report has already been handed in is refused outright");
  });
});

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 5. THE STATE WAS ALWAYS REPRESENTABLE, AND THE THREE PLACES THAT DRAW IT AGREE
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("the empty workshop reads the same before, during and after the save", () => {
  test("the saved-answer sentence handles an empty team and says what was kept", () => {
    /*
      `answer.designers.length === 0` was already written here and was unreachable, because the
      panel refused to send the body that produces it. It is a real outcome now, so it says what the
      workshop is LEFT like rather than only counting rows — and it answers, after the fact, the
      question the warning raised before the click.
    */
    expect(PROSE).toContain("Nobody is named on this workshop. Its report will name nobody on the cover");
    expect(
      PROSE,
      "the success sentence does not confirm the stage 1 / stage 3 details survived the removal"
    ).toContain("The profile details already copied into stages 1 and 3 stay exactly as they are");
    // A removal copies no profile, so the "copied into N stages" clause must live on the OTHER arm
    // of the same ternary — reporting a stage write that a removal does not perform would be this
    // repository's favourite defect wearing a success banner.
    expect(PAGE_CODE).toContain("const nowEmpty = answer.designers.length === 0;");
    expect(
      PROSE,
      "the stage-write count is no longer nested under `nowEmpty`, so an emptied workshop may report a copy"
    ).toMatch(/nowEmpty \? "The profile details[^"]*" : answer\.stagesWritten\.length/);
  });

  test("the empty-state copy is still there — it is the evidence the state was always expressible", () => {
    /*
      This paragraph predates the fix and was reachable the whole time, which is precisely the
      argument: a panel that could DRAW "nobody holds a designer row on this workshop" while
      refusing to let anybody reach that state was contradicting itself on one screen.
    */
    expect(PROSE).toContain("Nobody holds a designer row on this workshop");
    expect(PROSE).toContain("an empty list here is not the same as nobody at all");
  });
});

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 6. THE EMPTY BODY IS ACTUALLY EMPTY
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("unticking everybody sends nobody", () => {
  test("the baseline lead stands down when the selection is empty", () => {
    /*
      THE TRAP UNDER THE FIX, AND IT IS SILENT. `namedDesignerTeam({ chosen: [], lead: L })` returns
      `{ lead: L, team: [L] }` — correct for the create form, where a named lead IS the choice.
      Feeding it `leadChoice || baselineLead` here would have posted the very designer the officer
      had just unticked, under a 200, with the panel's own refusal gone and nothing on screen to
      say so. `leadIntent` is the one expression that decides it, and the picker reads the same
      value so its "the report will carry …" line cannot name somebody nobody ticked.
    */
    expect(PAGE_CODE, "`leadIntent` is gone; the resolver is being fed a lead over an empty team").toContain(
      'const leadIntent = selected.length === 0 ? "" : leadChoice || baselineLead;'
    );
    expect(PAGE_CODE).toContain("namedDesignerTeam({ chosen: selected, lead: leadIntent })");
    expect(PAGE_CODE, "the picker resolves its lead line from a different value than the save does").toContain(
      "lead={leadIntent}"
    );
    // `leadUserId` is sent only on a real move, and an empty team can no longer produce one.
    expect(PAGE_CODE).toContain("const leadMoved = Boolean(leadIntent) && leadIntent !== baselineLead;");
  });

  test("the save still posts the whole set and still never replaces it wholesale", () => {
    // Unchanged by this wave and asserted so it stays that way: the server diffs and removes one at
    // a time, because a whole-set replace destroys a viewer row a concurrent join-card redemption
    // created in the same second.
    expect(PAGE_CODE).toContain("userIds: resolved.team");
    expect(PAGE_CODE).toContain("leadMoved ? { leadUserId: resolved.lead } : {}");
  });
});
