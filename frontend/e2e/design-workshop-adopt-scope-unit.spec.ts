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

test("with no connection the list is still READ from the device, and says what it is", () => {
  /*
    The fallback LIST stays. A designer with one bar of signal, holding a stranded workshop, still
    sees what is on the device rather than "Loading…" through a request that will time out. What the
    sentence must do is refuse to present itself as the scoped answer — and, since the move is now
    held (the test below), say that too, because a disabled button whose reason is nowhere is the
    control people press repeatedly.
  */
  expect(dialog).toContain("setCandidates(knownRef.current);");
  expect(dialog).toContain("setPartial(true);");
  expect(dialog).toContain("cannot check whether");
  expect(dialog).toContain("Moving waits for signal");
});

test("A FAILED FETCH DOES NOT UNLOCK THE MOVE — the list may be read offline and never acted on", () => {
  /*
    THE DEFECT THIS PINS, AND IT IS THE ONE THIS FILE'S OWN HEADER FORBIDS ONE BRANCH EARLIER.

    `verified` used to be stamped by BOTH arms of `load`, on the reasoning that "a failure is an
    answer too". It is an answer about the NETWORK and no answer at all about ACCESS. So a fetch that
    failed handed the cached rows straight back as DESTINATIONS — the same rows defect 2 above
    removed from the merge, offered one branch later for the write that cannot be undone. A grant
    revoked in March was still choosable in September, and `localDraftNeedsAWorkshop` guards adoption
    on `remoteId === null`, so nothing in either client could point the draft anywhere else
    afterwards. `DROPDOWN_DESIGN.md` R6 cites this dialog as its authority for "never cache an ACCESS
    list" while this branch was the exception to it.

    WITHDRAWING IT COSTS THE DESIGNER NOTHING, which is what makes this the right way round rather
    than merely the stricter one. Adoption sends nothing: the draft stays on the device, nothing
    automatic may delete it, and no stage can reach the chosen workshop until there is a connection —
    the same moment the live list becomes available. Waiting changes when the button lights up and
    not when the fieldwork moves.
  */
  expect(dialog).toContain("const [verified, setVerified] = useState(false);");
  /*
    COUNTED RATHER THAN MATCHED, and that is the only form of this assertion that holds. A
    `toContain("setVerified(false)")` passes on the reset effect alone, which has always set it false
    when the dialog opens — so it would go on passing the day somebody stamps the catch again. There
    is exactly ONE place the live answer is claimed, and it is the success arm.
  */
  expect(dialog.match(/setVerified\(true\)/g) ?? []).toHaveLength(1);
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

test("it says plainly what linking does, including what happens on a COLLISION", () => {
  /*
    THIS TEST PINNED A SENTENCE THAT WAS NOT TRUE, and it is worth saying which one rather than
    quietly swapping the string. It required the dialog to promise *"the workshop keeps whatever is
    already in it"*. `adoptedIntoWorkshop` clears `serverLoadedAt`, so the first PUT carries
    `merge: true`, and `save_stage` folds the row as `{**previous, **clean}` — a key THIS DEVICE
    holds wins. So a box answered on both sides ends up with the draft's answer, and the promise was
    false for exactly the case a designer would want it for.

    The dialog now states the rule instead, and this asserts the three clauses that matter: nothing
    is deleted, what survives on the target, and who wins a box both sides answered. The argument for
    why local-wins is the right way round — the draft is the answer given in the room, the target's
    is a seed or a desk edit — is in the component's own header.
  */
  expect(dialog).toContain("Nothing is deleted and nothing is retyped");
  expect(dialog).toContain("answers already in\n          that workshop are kept");
  expect(dialog).toContain("where both have the same box, this device");
  expect(dialog).toContain("It can only be done once");
});

test("the row says what an unlinked draft IS and what to do with it, in two facts", () => {
  /*
    THE ROW'S PARAGRAPH USED TO BE THREE SENTENCES AND USED TO SEND EVERY DESIGNER TO AN ADMIN. It
    was written when the only drafts that could reach that row were the ones stranded by the create
    rule, whose owner genuinely had no workshop to move into. Since 2026-08-31 a designer may START
    one there deliberately, with no signal, and such a designer usually has workshops already — so
    the next move is the picker, not a conversation. The admin sentence survives on the one surface
    that KNOWS the account has nothing to move into (`nothingToMoveInto`, asserted above) and in the
    list's own empty state.

    Both clients read these two strings from one declaration, and
    `design-workshop-offline-start-unit.spec.ts` holds them against the handset's literals.
  */
  expect(page).toContain("{DW_LOCAL_DRAFT_UNLINKED} {DW_LOCAL_DRAFT_LINK_PROMPT}");
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
