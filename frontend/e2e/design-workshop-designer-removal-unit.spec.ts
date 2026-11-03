import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8");

function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
}

const PAGE = read("app/(protected)/officers/page.tsx");
const PAGE_CODE = stripComments(PAGE);

const PROSE = PAGE_CODE.replace(/\s+/g, " ");

const SAVE_DISABLED = (() => {
  const match = /disabled=\{([^{}]*)\}\s*\n\s*onClick=\{save\}/.exec(PAGE_CODE);
  expect(match, "the Save button's `disabled` expression is gone or was restructured").not.toBeNull();
  return match![1];
})();

const SAVE_GUARD = (() => {
  const match = /if \(([^)]*!dirty[^)]*)\) return;/.exec(PAGE_CODE);
  expect(match, "`save()` no longer opens with a guard clause; this test cannot do its job").not.toBeNull();
  return match![1];
})();

const WARNING_BRANCH = (() => {
  const match = /\) : emptiesTheWorkshop \? \(([\s\S]*?)\n\s*\) : null\}/.exec(PAGE_CODE);
  expect(match, "the empty-selection notice is not in the panel's notice chain").not.toBeNull();
  return match![1].replace(/\s+/g, " ");
})();

test.describe("an empty selection no longer disables Save", () => {
  test("`strandsTheDesigner` is not a predicate this panel computes any more", () => {
    expect(
      PAGE_CODE,
      "the empty-selection refusal is back in the code — emptying a workshop is an allowed transition"
    ).not.toContain("strandsTheDesigner");
  });

  test("nothing else in the panel silently disables Save on an empty selection", () => {
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

    for (const kept of ["overCap", "dropsTheLeadWithNoReplacement"]) {
      expect(SAVE_DISABLED, `\`${kept}\` no longer disables Save`).toContain(kept);
      expect(SAVE_GUARD, `\`${kept}\` no longer guards save()`).toContain(kept);
    }
  });
});

test.describe("emptying a workshop is warned about before the click", () => {
  test("the panel computes the state and renders a notice in the same slot", () => {
    expect(PAGE_CODE, "no predicate answers `this save will empty the workshop`").toContain(
      "const emptiesTheWorkshop ="
    );
    expect(PAGE_CODE).toContain("selected.length === 0 && removed.length > 0");
    expect(WARNING_BRANCH.length, "the notice branch came back empty — the parse is wrong, not the code").toBeGreaterThan(
      120
    );
  });

  test("all three consequences are stated in words", () => {
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
    expect(WARNING_BRANCH).toContain("bg-amber-100");
    expect(WARNING_BRANCH).toContain("text-amber-800");
    expect(WARNING_BRANCH).toContain("border-amber-500/30");
    expect(WARNING_BRANCH, "a stock amber rung crept into the tinted card").not.toMatch(
      /amber-(50|200|300|400|600|700|900)\b/
    );
    expect(WARNING_BRANCH, "the notice is painting itself an action colour").not.toContain("purple-700");
  });
});

test.describe("dropping the lead still needs a replacement — but only when there is one to name", () => {
  test("the predicate requires a non-empty resulting selection", () => {
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
    expect(PAGE_CODE).toContain("const emptiesTheWorkshop = selected.length === 0");
    expect(PAGE_CODE).toMatch(/dropsTheLeadWithNoReplacement =[\s\S]*?selected\.length > 0/);
  });
});

test.describe("the panel's own intro says what is now true", () => {
  test("the unconditional sentence is gone", () => {
    expect(PROSE, "the intro still states the removal rule unconditionally").not.toContain(
      "cannot simply be removed"
    );
  });

  test("the condition and the allowed act are both spelled out", () => {
    expect(PROSE, "the intro does not say when the lead cannot be dropped").toContain(
      "While other designers stay ticked, the lead cannot simply be dropped"
    );
    expect(PROSE, "the intro does not say that emptying the workshop is allowed").toContain(
      "also untick everybody"
    );
    expect(PROSE).toContain("If the report has already been handed in, saving isn&apos;t allowed");
  });
});

test.describe("the empty workshop reads the same before, during and after the save", () => {
  test("the saved-answer sentence handles an empty team and says what was kept", () => {
    expect(PROSE).toContain("Nobody is named on this workshop. Its report will name nobody on the cover");
    expect(
      PROSE,
      "the success sentence does not confirm the stage 1 / stage 3 details survived the removal"
    ).toContain("The profile details already copied into stages 1 and 3 stay exactly as they are");
    expect(PAGE_CODE).toContain("const nowEmpty = answer.designers.length === 0;");
    expect(
      PROSE,
      "the stage-write count is no longer nested under `nowEmpty`, so an emptied workshop may report a copy"
    ).toMatch(/nowEmpty \? "The profile details[^"]*" : answer\.stagesWritten\.length/);
  });

  test("the empty-state copy is still there — it is the evidence the state was always expressible", () => {
    expect(PROSE).toContain("No designer is named on this workshop");
    expect(PROSE).toContain("Whoever created it can still open it");
    expect(PROSE).not.toContain("designer row");
  });
});

test.describe("unticking everybody sends nobody", () => {
  test("the baseline lead stands down when the selection is empty", () => {
    expect(PAGE_CODE, "`leadIntent` is gone; the resolver is being fed a lead over an empty team").toContain(
      'const leadIntent = selected.length === 0 ? "" : leadChoice || baselineLead;'
    );
    expect(PAGE_CODE).toContain("namedDesignerTeam({ chosen: selected, lead: leadIntent })");
    expect(PAGE_CODE, "the picker resolves its lead line from a different value than the save does").toContain(
      "lead={leadIntent}"
    );
    expect(PAGE_CODE).toContain("const leadMoved = Boolean(leadIntent) && leadIntent !== baselineLead;");
  });

  test("the save still posts the whole set and still never replaces it wholesale", () => {
    expect(PAGE_CODE).toContain("userIds: resolved.team");
    expect(PAGE_CODE).toContain("leadMoved ? { leadUserId: resolved.lead } : {}");
  });
});
