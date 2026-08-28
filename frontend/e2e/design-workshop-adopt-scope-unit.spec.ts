import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { RENDER_CAP } from "@/components/ui/selectFilter";

/**
 * "MOVE INTO A WORKSHOP" MAY ONLY OFFER WORKSHOPS THE SERVER WOULD ACTUALLY ADMIT THIS ACCOUNT TO.
 *
 * ── THE FEATURE ─────────────────────────────────────────────────────────────────────────────────
 *
 * A design workshop a designer started with no signal is not a row: it is a `DwDraft` in this
 * browser's IndexedDB with `remoteId === null`. Starting a workshop became an admin's job, so those
 * drafts can never create themselves — they are ADOPTED instead. An admin creates the workshop and
 * the designer points the draft at it, after which every stage, photograph and recorded deletion
 * reaches it by the ordinary sync path. `adoptDraftIntoWorkshop` is the write and
 * `AdoptLocalDraftDialog` is the choosing.
 *
 * ── WHY THE CHOOSING IS THE DANGEROUS HALF ──────────────────────────────────────────────────────
 *
 * Adoption is ONE-WAY AND UNREPEATABLE: `localDraftNeedsAWorkshop` guards it on `remoteId === null`,
 * so once a draft has been pointed somewhere nothing in either client can point it anywhere else.
 * Choosing the wrong destination files a fortnight of fieldwork under another cluster, permanently.
 *
 * That is why the two defects this file pins are not cosmetic:
 *
 *   1. **A CLIENT-SIDE FILTER OVER A SERVER-TRUNCATED LIST** (§11.5 of the frontend contract). The
 *      dialog fetched one page of workshops and passed `searchable` to a control that draws
 *      `RENDER_CAP` rows, so typing the name of a workshop that exists answered "No matches" — and
 *      nothing on screen said the list had been cut. `GET /design-workshops` takes `search` over
 *      title, craft, cluster and workshop code, so the box belongs above the picker, wired to the
 *      server, with the control's own filter off and a `capHint` naming the box that DOES reach the
 *      rest.
 *   2. **CACHED ROWS MERGED INTO THE SERVER'S ANSWER.** A design workshop is visible only to its
 *      creator, to admins, and to whoever holds a `DesignWorkshopViewer` row. This device's list of
 *      workshops is the server's answer AS OF THE LAST SYNC and it is stale in the PERMISSIVE
 *      direction — a grant revoked in March is still here in September. The workshop list on this
 *      same route already withdrew that fallback for exactly this reason; offering it as a
 *      DESTINATION is worse, because the write cannot be undone.
 *
 * ── WHY THESE ARE SOURCE ASSERTIONS ─────────────────────────────────────────────────────────────
 *
 * There is no React renderer in this project's devDependencies. The checks below are PRESENCE
 * checks on exact source wherever possible, which this repository's prose cannot accidentally
 * satisfy. The one absence check names a code fragment (`[...known, ...rows]`) that appears in no
 * sentence anywhere in the tree — see `qr-surfaces-unit.spec.ts` for why an absence check over
 * ordinary words would be unsafe here.
 */

const root = join(__dirname, "..");

/**
 * Newlines normalised, because this checkout is CRLF: a multi-line `toContain` written the way it
 * reads on screen would match nothing, and would fail about source that is perfectly correct.
 */
function sourceOf(...parts: string[]): string {
  return readFileSync(join(root, ...parts), "utf8").replace(/\r\n/g, "\n");
}

const dialog = sourceOf("components", "designworkshop", "AdoptLocalDraftDialog.tsx");
const page = sourceOf("app", "(protected)", "design-workshops", "page.tsx");

test("the search box is the SERVER'S, and the picker's own filter is off", () => {
  expect(dialog).toContain("<SearchInput");
  expect(dialog).toContain("search: term || undefined");
  expect(dialog).toContain("searchable={false}");
  expect(dialog).toContain('capHint="Use the search box above to reach the rest');
});

test("it asks for exactly as many rows as the control draws", () => {
  /*
    NOT A ROUND NUMBER. Asking for 100 rows into a control that renders 80 printed two truncation
    sentences with two different totals, one above the other, and said nothing at all between 81 and
    100. `RENDER_CAP` is imported here rather than restated so this assertion cannot pass while the
    dialog and the control disagree.
  */
  expect(RENDER_CAP).toBe(80);
  expect(dialog).toContain("pageSize: RENDER_CAP");
  expect(dialog).toContain('import { RENDER_CAP } from "@/components/ui/selectFilter";');
});

test("a truncated list says so, and names the total rather than just the window", () => {
  // Rule 10: a list that quietly stops is indistinguishable from a place with no records. And
  // "showing the first 80" without a total is a cap nobody can reason about — one missing workshop
  // and four hundred read identically.
  expect(dialog).toContain("setTotal(found.total)");
  expect(dialog).toContain("Showing ${shown} of ${total} workshops open to you");
});

test("the server's answer is NOT merged with what this browser happens to have cached", () => {
  /*
    The merge that used to live here. Its removal is the whole of defect 2 above, and an absence
    check is safe for this one fragment because it is code and appears in no prose in the tree.
  */
  expect(dialog).not.toContain("[...known, ...rows]");
  expect(dialog).toContain("setCandidates(found.items.map((row) => ({ id: row.id, label: labelFor(row) })))");
});

test("with no connection it falls back to the device AND says the list cannot be trusted", () => {
  /*
    The fallback stays — a designer with one bar of signal holding a stranded workshop must not be
    told to come back when they have wifi. What it must not do is present itself as the scoped
    answer, so the sentence now says both halves: the list is partial, and the repository cannot be
    asked whether this account is still on any of it.
  */
  expect(dialog).toContain("setCandidates(knownRef.current);");
  expect(dialog).toContain("setPartial(true);");
  expect(dialog).toContain("cannot be asked whether you are still on them");
});

test("the one-way write is held until the repository has answered, though the list is readable at once", () => {
  /*
    THE SAME RULE AS THE REMOVED MERGE, APPLIED TO THE FIRST FRAME. The picker paints this device's
    cached workshops immediately — somebody with no signal must not stare at "Loading…" through a
    request that is going to time out — but a cached row is stale in the permissive direction and
    adoption cannot be undone. So the list may be READ early and may not be ACTED ON early: `Move`
    is disabled until one answer has landed, and a FAILURE counts as an answer because the partial
    notice then says exactly what the list is.
  */
  expect(dialog).toContain("const [verified, setVerified] = useState(false);");
  expect(dialog).toContain("disabled={busy || !chosen || !verified}");
  expect(dialog).toContain("Checking which workshops are open to you…");
});

test("opening the dialog costs exactly one request, and a colleague's autosave costs none", () => {
  /*
    `drafts` is the whole live draft store, so `known` changes on every stage autosave in every other
    tab. With `known` in `load`'s dependency array, `load` was a new function each time and the
    debounced search effect tore itself down and re-issued — a `%term%` scan of the workshop table
    every time somebody typed a sentence into stage 7, and the answer landing under a choice the
    designer had already made. Reading it through a ref leaves the search term as the only trigger.
  */
  expect(dialog).toContain("const knownRef = useRef(known);");
  expect(dialog).toContain("}, [search, open, load]);");
});

test("the amber notices use the brand rungs, which invert, not stock Tailwind amber", () => {
  // §3.5: `amber` deep-merges with stock Tailwind, and only 100/500/800 are brand. `bg-amber-50`
  // does not invert, so the notice was unreadable in dark mode.
  expect(dialog).toContain("bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800");
});

test("the dialog does not offer itself where it would do nothing", () => {
  /*
    An empty picker over an empty scope is the silent-emptiness state rule 10 forbids, and here it
    reads to a designer as the app having lost their workshops. The honest answer is that no
    workshop has been opened for them YET, and the next move — an admin creating one and NAMING them
    on it — is the one thing that changes it. The Move button goes with the picker: a button that
    can never be enabled is a dead control with an explanation somewhere else on the screen.
  */
  expect(dialog).toContain("const nothingToMoveInto =");
  expect(dialog).toContain("No design workshop is open to this account yet");
  expect(dialog).toContain("{nothingToMoveInto ? null : (");
});

test("it says plainly what linking does, including what it does NOT do", () => {
  expect(dialog).toContain("Nothing is deleted by this and nothing is retyped");
  expect(dialog).toContain("the workshop keeps whatever\n          is already in it");
  expect(dialog).toContain("It can only be done once");
});

test("the row's control is still offered only to an account that cannot create a workshop", () => {
  /*
    UNCHANGED, AND CHECKED BECAUSE IT IS THE OTHER HALF OF "must not offer itself where it would do
    nothing". An admin holding a device-only draft does not need this: their next sync creates the
    workshop and the draft resolves itself. Offering them a control that quietly re-files a
    fortnight of fieldwork into a DIFFERENT workshop, for no benefit, is a way to lose work by
    mis-tap.
  */
  expect(page).toContain("const offerMove = !allowCreate && allowWork;");
  expect(page).toContain("{offerMove && orphanDrafts.has(workshop.id) ? (");
});

test("the page tells a stranded designer the whole next move, access included", () => {
  // A design workshop is visible only to the designers named on it, so "ask an admin to create it"
  // is only half an instruction: a workshop created without them on it is one they cannot see here
  // and therefore cannot move anything into.
  expect(page).toContain("name you as one of its designers");
});
