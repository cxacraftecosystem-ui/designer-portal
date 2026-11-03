import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { RENDER_CAP } from "@/components/ui/selectFilter";

const root = join(__dirname, "..");

function sourceOf(...parts: string[]): string {
  return readFileSync(join(root, ...parts), "utf8").replace(/\r\n/g, "\n");
}

const dialog = sourceOf("components", "designworkshop", "AdoptLocalDraftDialog.tsx");
const page = sourceOf("app", "(protected)", "design-workshops", "page.tsx");

test("the search box is the SERVER'S, and the picker's own filter is off", () => {
  expect(dialog).toContain("<SearchInput");
  expect(dialog).toContain("search: term || undefined");
  expect(dialog).toContain("searchable={false}");
  expect(dialog).toContain('capHint="Use the search box above to find the rest');
});

test("it asks for exactly as many rows as the control draws", () => {
  expect(RENDER_CAP).toBe(80);
  expect(dialog).toContain("pageSize: RENDER_CAP");
  expect(dialog).toContain('import { RENDER_CAP } from "@/components/ui/selectFilter";');
});

test("a truncated list says so, and names the total rather than just the window", () => {
  expect(dialog).toContain("setTotal(found.total)");
  expect(dialog).toContain("Showing ${shown} of ${total} workshops open to you");
});

test("the server's answer is NOT merged with what this browser happens to have cached", () => {
  expect(dialog).not.toContain("[...known, ...rows]");
  expect(dialog).toContain("setCandidates(found.items.map((row) => ({ id: row.id, label: labelFor(row) })))");
});

test("with no connection the list is still READ from the device, and says what it is", () => {
  expect(dialog).toContain("setCandidates(knownRef.current);");
  expect(dialog).toContain("setPartial(true);");
  expect(dialog).toContain("can&rsquo;t check whether");
  expect(dialog).toContain("You can move it once you&rsquo;re back online");
});

test("A FAILED FETCH DOES NOT UNLOCK THE MOVE — the list may be read offline and never acted on", () => {
  expect(dialog).toContain("const [verified, setVerified] = useState(false);");
  expect(dialog.match(/setVerified\(true\)/g) ?? []).toHaveLength(1);
  expect(dialog).toContain("disabled={busy || !chosen || !verified}");
  expect(dialog).toContain("Checking which workshops are open to you…");
});

test("opening the dialog costs exactly one request, and a colleague's autosave costs none", () => {
  expect(dialog).toContain("const knownRef = useRef(known);");
  expect(dialog).toContain("}, [search, open, load]);");
});

test("the amber notices use the brand rungs, which invert, not stock Tailwind amber", () => {
  expect(dialog).toContain("bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800");
});

test("the dialog does not offer itself where it would do nothing", () => {
  expect(dialog).toContain("const nothingToMoveInto =");
  expect(dialog).toContain("No design workshop is open to you");
  expect(dialog).toContain("{nothingToMoveInto ? null : (");
});

test("it says plainly what linking does, including what happens on a COLLISION", () => {
  expect(dialog).toContain("Nothing is deleted");
  expect(dialog).toContain("answers already in that workshop are kept");
  expect(dialog).toContain("where both have the same box, this device");
  expect(dialog).toContain("It can only be done once");
});

test("the row says what an unlinked draft IS and what to do with it, in two facts", () => {
  expect(page).toContain("{DW_LOCAL_DRAFT_UNLINKED} {DW_LOCAL_DRAFT_LINK_PROMPT}");
});

test("the row's control is still offered only to an account that cannot create a workshop", () => {
  expect(page).toContain("const offerMove = !allowCreate && allowWork;");
  expect(page).toContain("{offerMove && orphanDrafts.has(workshop.id) ? (");
});

test("the page tells a stranded designer the whole next move, access included", () => {
  expect(page).toContain("Ask an admin to create one and add you as a designer.");
});
