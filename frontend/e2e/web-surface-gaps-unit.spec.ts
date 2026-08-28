import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { dwDictationAllowance, forgetDictationAllowanceInFlight } from "@/lib/designWorkshops";

/**
 * FIVE THINGS THE SERVER OR THE HANDSET COULD DO AND THE BROWSER COULD NOT.
 *
 * Each one is the same shape of defect — a feature complete everywhere except its call site — and
 * each was found by grepping the client trees for a route that has no caller:
 *
 *  1. **Cost integrity.** `cost_integrity.py` computes it, `DwFindingsPanel.kt` shows it on every
 *     handset, and `grep -rn "cost-integrity|costIntegrity|CostIntegrity" frontend` returned ZERO.
 *     A designer typing a material subtotal of ₹1,560 with lines that come to ₹1,650 was warned on a
 *     phone and not on a laptop, about the same workshop, on the figure the report prints into a
 *     document submitted to a Development Commissioner's office. `GET
 *     /design-workshops/{id}/cost-integrity` had no caller on EITHER surface.
 *  2. **The dictation allowance.** `GET /design-workshops/dictation-allowance` opens its docstring
 *     with "**THIS ROUTE IS WHY THE CAP IS NOT JUST A 429**" — and had no client at all, so both
 *     surfaces went on learning the ceiling by spending a multi-megabyte upload to be refused.
 *  3. **Withdrawing a comment.** `DELETE /data-access/comments/{id}` sits beside a GET and a POST
 *     that both have callers on both clients. It had none, so a comment posted on the wrong record
 *     was permanent.
 *  4. **A way back into a stage from the report preview.** The only stage link on that screen was
 *     rendered inside the `incomplete.length ?` branch, so it named stages with a MISSING answer —
 *     and the case a designer reads the preview to catch is the opposite one, a participant village
 *     that is filled in and wrong.
 *
 *  5. **Tracing a sketch from the stage form.** `SketchTraceField` — the tracing panel, its four
 *     comparison views, the magnifier, the difference plate and the export formats — was mounted
 *     from `UploadTabPanel` and from nowhere else, so `grep -rln SketchTraceField frontend/components`
 *     answered with that panel's own directory and no host. A designer filling in stage 11 in the
 *     record form could not reach any of it, while the handset has mounted its own trace panel from
 *     `FieldRenderer.kt` on that same field all along. Everything below the mount was finished and
 *     tested; the fifth defect, like the other four, was the call site.
 *
 * The arithmetic half of (1) is proved equal to the backend, case for case, in
 * `cost-integrity-port-unit.spec.ts`. What is asserted here is that each feature is WIRED — which is
 * precisely the half that was missing, and which no amount of correct arithmetic supplies.
 *
 * Source reads, for the reason `discarded-work-unit.spec.ts` states plainly: there is no React
 * renderer in this repository's devDependencies, so a component cannot be mounted. Every assertion
 * names a substring the tree did not contain before the fix.
 */

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const STAGE_PAGE = "app/(protected)/design-workshops/[id]/stages/[stageKey]/page.tsx";
const REPORT_PAGE = "app/(protected)/design-workshops/[id]/report/page.tsx";

/* ────────────────────────────────────────────────────────────────────────────
 * 1. Cost integrity reaches the browser
 * ──────────────────────────────────────────────────────────────────────────── */

test("stage 17 mounts a cost panel the way stage 9 mounts the market one", () => {
  const page = read(STAGE_PAGE);
  expect(page).toContain("COSTING_STAGE, CostFindingsPanel");
  expect(page).toContain("{stageKey === COSTING_STAGE ? (");
  expect(page).toContain("<CostFindingsPanel workshopId={id} collections={collections} />");
});

test("the panel names the stage the server names", () => {
  // `COSTING_MARKET_LINKAGE` is `STAGE_COSTING` in `DwFindingsPanel.kt`. Two clients keying the same
  // panel off two spellings of one stage is how one of them silently stops rendering.
  expect(read("components/designworkshop/CostFindingsPanel.tsx")).toContain(
    'export const COSTING_STAGE = "COSTING_MARKET_LINKAGE";'
  );
});

test("the endpoint finally has a caller", () => {
  const lib = read("lib/designWorkshops.ts");
  expect(lib).toContain("export function dwCostIntegrity(id: string)");
  expect(lib).toContain("`/design-workshops/${id}/cost-integrity`");
  expect(read("components/designworkshop/CostFindingsPanel.tsx")).toContain("dwCostIntegrity(workshopId)");
});

test("the panel writes nothing back into stage 17", () => {
  /*
    THE INVARIANT THE ENDPOINT'S OWN DOCSTRING HOLDS: "a subtotal may legitimately differ from its
    lines, and silently replacing a considered figure with a computed one would be a worse bug than
    the one being fixed." The designer was in the room when the figure was decided; the arithmetic
    was not, and the report must go on printing what they typed. The panel therefore takes no write
    callback of any kind — there is nothing for a mis-tap to reach.
  */
  const panel = read("components/designworkshop/CostFindingsPanel.tsx");
  expect(panel).not.toContain("onChange");
  expect(panel).not.toContain("onPatch");
  expect(panel).not.toContain("saveStage");
  // And the props it does take are exactly two, both read-only.
  expect(panel).toContain("workshopId: string;");
  expect(panel).toContain("collections: Record<string, DwRow[]>;");
});

test("an unsynced sheet suppresses the deleted-sheet caution but never a warning", () => {
  /*
    A warning is a sheet contradicting its own lines — a fact about figures on this screen, unaffected
    by what has synced. Every CAUTION is an orphan caution and every orphan caution offers "the sheet
    they named may have been deleted" as the explanation, which is a false alarm about a morning's
    costing when the sheet is three rows up waiting for a tower. The handset makes the same
    distinction with the same count; the port's sentences are never edited to say it.
  */
  const panel = read("components/designworkshop/CostFindingsPanel.tsx");
  expect(panel).toContain("{findings.warnings.length ? (");
  expect(panel).toContain("{!unsynced && findings.cautions.length ? (");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The dictation allowance is asked for before it is spent
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Stand in for the network for the length of one test, and report what was asked of it.
 *
 * EXECUTED RATHER THAN READ, which is the point of this block. The three tests below used to assert
 * that `Dictation.tsx` contained the substrings `if (mode !== "server") return;` and `}, [mode];`,
 * under a title claiming the request "is asked once" — and NOTHING in those substrings could fail
 * when the request was issued eleven times on one stage, which is what the code did on any browser
 * without a native recogniser. `mode` is per-button state; on Firefox every microphone on stage 13
 * resolves to `server`. The assertions pinned the spelling of a gate that did not deliver the
 * property the title named.
 *
 * `dwDictationAllowance` reaches the network through the global `fetch`, so replacing that is enough
 * to count requests, and no dev server is needed for it.
 */
function interceptFetch(answer: () => Promise<Response>) {
  const calls: string[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = ((input: RequestInfo | URL) => {
    calls.push(String(input));
    return answer();
  }) as typeof fetch;
  return { calls, restore: () => { globalThis.fetch = original; } };
}

const jsonResponse = (body: unknown) =>
  new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });

const ALLOWANCE = { dictationsLimit: 20, dictationsUsed: 3, dictationsRemaining: 17, dictationDay: "2026-08-19" };

test("eleven microphones on one stage ask the server ONCE between them", async () => {
  /*
    STAGE 13 DRAWS ELEVEN OF THESE, and this is the number the pre-flight's comment has always
    claimed and only now delivers. Eleven concurrent callers — which is what eleven mount effects
    resolving off one shared `serverOffersRoute` promise produce — must reach the network once.

    The second expectation is worth as much as the first: they all get the SAME answer, so eleven
    readouts on one stage cannot disagree with each other about how many dictations are left.
  */
  forgetDictationAllowanceInFlight();
  const net = interceptFetch(() => Promise.resolve(jsonResponse(ALLOWANCE)));
  try {
    const answers = await Promise.all(Array.from({ length: 11 }, () => dwDictationAllowance()));
    expect(net.calls.length, "eleven callers, one round trip").toBe(1);
    expect(net.calls[0]).toContain("/design-workshops/dictation-allowance");
    for (const answer of answers) expect(answer).toEqual(ALLOWANCE);
  } finally {
    net.restore();
    forgetDictationAllowanceInFlight();
  }
});

test("the shared request is released once it settles, so a later stage gets a fresh count", async () => {
  /*
    THE DELIBERATE LIMIT OF THE DEDUPE, asserted so that "asked once per SESSION" is not quietly
    introduced later under the same comment. Only the in-flight promise is shared. A retained answer
    would show a designer who spent four dictations on stage 13 the count from before those four,
    and — because a field laptop is shared and `AuthProvider.logout` clears the token and nothing
    else — would follow one designer's account into the next one's session.
  */
  forgetDictationAllowanceInFlight();
  const net = interceptFetch(() => Promise.resolve(jsonResponse(ALLOWANCE)));
  try {
    await dwDictationAllowance();
    await dwDictationAllowance();
    expect(net.calls.length, "two separate mounts, two requests").toBe(2);
  } finally {
    net.restore();
    forgetDictationAllowanceInFlight();
  }
});

test("a courtesy pre-flight that fails resolves null and never throws", async () => {
  /*
    A caller drawing a microphone must not LOSE the microphone because this request failed; the
    reactive 429 handling in `Dictation.tsx` is still the authority on a refusal. Both doors are
    tried, because they arrive differently: a deployment that predates the route answers 404 (an
    `ApiError` thrown by `apiFetch`) and a courtyard with no signal rejects the fetch outright.

    And the failure must not be cached: the NEXT caller asks again, or one bad moment on a mobile
    connection silences the readout for the rest of the session.
  */
  forgetDictationAllowanceInFlight();
  const offline = interceptFetch(() => Promise.reject(new TypeError("Failed to fetch")));
  try {
    await expect(dwDictationAllowance()).resolves.toBeNull();
  } finally {
    offline.restore();
  }
  const notDeployed = interceptFetch(() =>
    Promise.resolve(new Response(JSON.stringify({ detail: "Not Found" }), {
      status: 404,
      headers: { "content-type": "application/json" }
    }))
  );
  try {
    await expect(dwDictationAllowance()).resolves.toBeNull();
    expect(notDeployed.calls.length, "the failure is not cached as an answer").toBe(1);
  } finally {
    notDeployed.restore();
    forgetDictationAllowanceInFlight();
  }
});

test("the pre-flight is gated on the rung the cap governs, and nothing else is", () => {
  /*
    THE OTHER HALF, WHICH IS ABOUT RELEVANCE AND NOT VOLUME — and which stays a source read for the
    reason this file's header gives: the rule lives inside a component body and there is no React
    renderer here to mount it in. A browser recogniser spends no server allowance, so in `browser`
    mode there is no number to read out and nothing to ask. The volume half is the three executed
    tests above; this one would pass with the dedupe reverted and does not claim otherwise.
  */
  const dictation = read("components/designworkshop/Dictation.tsx");
  expect(dictation).toContain('if (mode !== "server") return;');
  expect(dictation).toContain("void dwDictationAllowance().then");
  // And the readout is drawn from the same gate, so a browser-mode button prints no cap either.
  expect(dictation).toContain('mode === "server" && allowance');
});

test("the readout prints a remaining count and the day it resets, and prints nothing when uncapped", () => {
  const dictation = read("components/designworkshop/Dictation.tsx");
  // `dictationsRemaining` is null on an uncapped deployment and the server keeps null and 0 apart
  // deliberately: printing "0 left" for "no ceiling" would turn a capability into a refusal.
  expect(dictation).toContain('mode === "server" && allowance && allowance.dictationsRemaining !== null');
  expect(dictation).toContain("allowance.dictationDay");
  // The 200 on a successful dictation carries the same four keys, so the count ages by at most one
  // dictation without a second round trip.
  expect(dictation).toContain('if (typeof result.dictationDay === "string")');
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. A comment can be withdrawn
 * ──────────────────────────────────────────────────────────────────────────── */

test("the delete route has a client and is offered only where the server would allow it", () => {
  const panel = read("components/CollabPanel.tsx");
  expect(panel).toContain('apiFetch(`/data-access/comments/${comment.id}`, { method: "DELETE" })');
  // The handler 403s unless the caller is the comment's AUTHOR or an admin. Offering the control more
  // widely would put a button in front of people whose only possible outcome is a refusal.
  expect(panel).toContain("user && (c.authorId === user.id || isAdmin(user))");
  // Confirmed first, in the danger tone: the row is deleted outright rather than tombstoned.
  expect(panel).toContain("deleteConfirm(");
  // Refetched rather than spliced out locally, so what is on screen is what the server holds.
  expect(panel).toContain("await load();");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. A way back into a stage from the report preview
 * ──────────────────────────────────────────────────────────────────────────── */

test("the preview links every stage, not only the unfinished ones", () => {
  const page = read(REPORT_PAGE);
  const completeness = page.slice(page.indexOf("Required fields across all 22 stages"));
  // The list that already existed is inside the `incomplete.length ?` branch and names only stages
  // with a MISSING answer. This one is outside it and is built from the registry.
  expect(completeness).toContain("Open a stage to correct something the preview shows");
  expect(completeness).toContain("{(registry?.stages ?? []).map((stage) => (");
  expect(completeness).toContain("href={`/design-workshops/${id}/stages/${stage.key}`}");
});

test("the way into a stage is a link, and the preview says what it is showing", () => {
  /*
    AN EDIT MUST LAND ON THE STAGE ENTRY so `merge_entry_provenance` moves authorship to the
    designer, and the printed value must stay the entry's own frozen COPY. A preview that wrote
    through to the repository record behind a reference would re-resolve a document that may already
    be in an officer's hands, which is the one thing this application must never do. So the way in is
    a link to the stage, and the sheet itself stays read-only.

    BOTH ASSERTIONS BELOW ARE ABOUT THE BLOCK THIS LANE ADDED, and that is the repair: this test
    also asserted `ReportSheet.tsx` does not contain "contentEditable", which is true at HEAD, was
    true before this lane and would be true with the whole of it reverted. Nothing proposed adding
    one. It reported safety it was not providing — see the standing tripwire below, where it now
    lives under a title that does not claim to be evidence of this change.
  */
  const page = read(REPORT_PAGE);
  const completeness = page.slice(page.indexOf("Required fields across all 22 stages"));
  // The sentence that tells a designer WHY correcting a value means opening the stage: what is on
  // the page is the stage entry's frozen copy, not the record it was copied from.
  expect(completeness).toContain("not the repository records they were");
  // And the way in is a navigation to the stage, not a control on the sheet.
  expect(completeness).toContain("href={`/design-workshops/${id}/stages/${stage.key}`}");
});

test("STANDING TRIPWIRE: nothing on the report screen is directly editable", () => {
  /*
    NOT EVIDENCE FOR ANY CHANGE, AND LABELLED SO. Neither the sheet nor the page has ever held an
    editable surface and nothing has proposed one; this passes with every lane in this file reverted.
    It is kept because the failure it guards is silent and expensive — a `contentEditable` on the
    preview would let a designer "fix" a printed value without it reaching `merge_entry_provenance`,
    so the .docx and the stage entry would disagree with each other and the change would have no
    author. A tripwire that has never fired is worth keeping; a tripwire dressed up as proof is not.
  */
  expect(read("components/designworkshop/report/ReportSheet.tsx")).not.toContain("contentEditable");
  const page = read(REPORT_PAGE);
  expect(page).not.toContain("contentEditable");
  // `ReportSheets` is handed presentation and nothing that could write: no callback of any kind.
  const mount = page.slice(page.indexOf("<ReportSheets"), page.indexOf("<ReportSheets") + 400);
  expect(mount).not.toMatch(/on[A-Z]/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5. The tracing panel is reachable from the record form
 * ──────────────────────────────────────────────────────────────────────────── */

const FIELD_INPUT = "components/designworkshop/FieldInput.tsx";

test("the record form mounts the tracing panel, not only the Sketches workspace", () => {
  /*
    THE WHOLE DEFECT WAS ONE ABSENT LINE, which is what makes it worth a test rather than a comment:
    nothing about the panel was broken, it simply had one host, and the host it lacked is the screen
    a designer actually fills in. `grep -rln SketchTraceField frontend/components` named only
    `components/sketches/upload/` before this.
  */
  const form = read(FIELD_INPUT);
  expect(form).toContain('import { SketchTraceField } from "@/components/sketches/upload/SketchTraceField";');
  expect(form).toContain("<SketchTraceField targetLabel={field.label} disabled={disabled} onAttach={attach} />");
});

test("both derived-drawing panels are gated by ONE predicate, as they are on the handset", () => {
  /*
    `dwOffersSketchTrace` IS `dwOffersSketchRectify` on the handset, and its own KDoc gives the
    reason: both panels write a derived artefact into the same destination, so a second rule here
    would be a second copy of the decision that this may never be offered on `sketch.image` — where
    attaching would DETACH the photograph the drawing was made from.
  */
  const form = read(FIELD_INPUT);
  expect(form).toContain("const offersDerivedDrawing = offersSketchRectify(entity, field);");
  expect(form).toContain("const sketchSources: SketchSource[] = offersDerivedDrawing");
  // And there is exactly one line-art rule in the client, in the role helper, not two.
  expect(form).not.toContain("offersSketchTrace");
});

test("the tracing panel is NOT gated on there being a sibling photograph, and rectify still is", () => {
  /*
    THE ONE PLACE THE TWO PANELS GENUINELY DIFFER, and the reason the predicate above had to be
    hoisted rather than reused as `sketchSources.length`. Rectifying straightens a photograph that
    is already attached to a sibling field, so with none there is nothing for it to do. Tracing
    brings its own photograph through its own picker — gating it on the source count would leave a
    working control invisible until an unrelated field was filled, which is this repository's
    "unreachable is not shipped" rule producing the very state it exists to prevent.
  */
  const form = read(FIELD_INPUT);
  const rectify = form.indexOf("<SketchRectifyField");
  const trace = form.indexOf("<SketchTraceField targetLabel");
  expect(rectify).toBeGreaterThan(-1);
  expect(trace).toBeGreaterThan(rectify);
  // The rectify mount is inside the `sketchSources.length ?` branch; the trace mount is not.
  expect(form.slice(0, rectify)).toContain("{sketchSources.length ? (");
  expect(form.slice(rectify, trace)).toContain("{offersDerivedDrawing ? (");
});

test("the record form does NOT hand the panel the photograph as well", () => {
  /*
    `onAttachSource` exists for a host with no other picker for the photograph — the Upload tab. A
    record form has an image field and its capture card has already filed the bytes there, so
    passing it here would file the same photograph under two names. The panel's own prop
    documentation says exactly this, and the absence is the thing that has to stay true.
  */
  const form = read(FIELD_INPUT);
  // SCOPED TO THE ELEMENT, not to the file: the mount's own comment names the prop in order to say
  // why it is absent, and a bare file-wide search would be satisfied by deleting that explanation.
  const start = form.indexOf("<SketchTraceField");
  const mount = form.slice(start, form.indexOf("/>", start) + 2);
  expect(mount).not.toContain("onAttachSource");
  expect(mount).toContain("onAttach={attach}");
  // The Upload tab, which genuinely is the only picker on its screen, still passes it.
  expect(read("components/sketches/upload/UploadTabPanel.tsx")).toContain("onAttachSource");
});
