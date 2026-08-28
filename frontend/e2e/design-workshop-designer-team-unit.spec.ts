import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  DW_DRAFT_SCHEMA_VERSION,
  adoptedIntoWorkshop,
  newLocalDraft,
  type DwDraft
} from "@/lib/designWorkshopStore";
import { MAX_NAMED_DESIGNERS, designerCreateFields, namedDesignerTeam } from "@/lib/designWorkshops";

/**
 * THE DESIGNERS A WORKSHOP IS OPENED FOR: several may OPEN it, exactly one name is ON it.
 *
 * ── WHAT CHANGED, AND WHY IT IS A SECURITY BOUNDARY RATHER THAN A PICKER PREFERENCE ─────────────
 *
 * A design workshop is visible ONLY to its creator, to admins, and to whoever holds a
 * `DesignWorkshopViewer` row — enforced IN THE QUERY on the list (`visible_to_clause`) and in
 * `load_workshop_or_404` on the single read, which refuses with a 404 byte-identical to a
 * nonexistent id so that the refusal cannot say whether the workshop is there. A DESIGNER cannot
 * create a workshop at all, so `createdById` never matches for them: the workshops a designer can
 * see are exactly the ones they hold a row on. Naming somebody on the create is therefore the whole
 * of how they get in, and the create writes one row per name in the same call.
 *
 * The create used to accept ONE name. A real Design & Prototype Development Workshop is a fortnight
 * of work by two designers alongside a master craftsperson, so the second designer had to be added
 * afterwards from "Designers on a workshop" — and an admin who forgot left a designer locked out of
 * a workshop whose stage 1 already carried their colleague's name.
 *
 * ── AND YET `designerName` IS STILL ONE STRING, DELIBERATELY ────────────────────────────────────
 *
 * `seed_designer_prefill` copies ONE `DesignerProfile` into stage 1 and stage 3 — one
 * `designerName`, one `designerProfile`, one signature — and `report_meta` feeds that name into the
 * .docx's `dc:creator`, a single-author field the file format cannot express as a list. So there is
 * a LEAD, and {@link namedDesignerTeam} is the rule that resolves it. Making the stage-1 designer
 * block repeatable is a registry change — it moves `registry_version()`, stales the schema asset
 * bundled into every APK, breaks two pinned Android tests and moves every existing workshop's
 * completeness — and is a separate wave and the owner's call.
 *
 * ── WHY THIS IS A NODE SPEC ─────────────────────────────────────────────────────────────────────
 *
 * There is no React renderer in this project's devDependencies, so a decision written inside JSX is
 * a decision no test can reach. The rule therefore lives in `lib/designWorkshops.ts` as two pure
 * functions and is exercised here — the same split, and the same reason, as
 * `components/ui/selectFilter.ts` and `components/data/cappedList.ts`. What genuinely cannot be
 * asserted without a browser (that the multi-select is mounted, that the search box is the
 * server's, that the themed control arms the dirty flag by hand) is checked as PRESENCE against the
 * exact source, never as absence — see the warning in `qr-surfaces-unit.spec.ts` about why an
 * absence check over this repository's prose is unsafe.
 */

const A = "ckuser0000000000000000aaa";
const B = "ckuser0000000000000000bbb";
const C = "ckuser0000000000000000ccc";

/* ────────────────────────────────────────────────────────────────────────────
 * 1. Who may open it, and whose name is on it
 * ──────────────────────────────────────────────────────────────────────────── */

test("nobody ticked is a real answer, and it names nobody", () => {
  expect(namedDesignerTeam({ chosen: [], lead: "" })).toEqual({ lead: "", team: [] });
});

test("one designer ticked is the lead by default — never the admin who pressed create", () => {
  /*
    The server promotes the FIRST NAMED designer when no lead is sent, because the only other
    candidate is the account that pressed create. An admin opening a workshop on somebody else's
    behalf having their own name copied into stage 1 and onto the report cover is the
    wrong-name-on-a-ministry-document defect this whole field exists to end.
  */
  expect(namedDesignerTeam({ chosen: [A], lead: "" })).toEqual({ lead: A, team: [A] });
});

test("the lead is put first, so the wire says which one it is rather than implying it", () => {
  expect(namedDesignerTeam({ chosen: [A, B, C], lead: B })).toEqual({ lead: B, team: [B, A, C] });
});

test("unticking the lead promotes the first ticked — it does NOT put them back", () => {
  /*
    THE DIRECTION AN ACCESS CONTROL MUST NEVER DRIFT. An admin who names a lead and then unticks
    them has REMOVED that designer; re-adding them because the id is still held in `lead` would put
    somebody on a workshop after the admin took them off it, and the workshop is only visible to the
    people on it. So the stale lead is dropped, not honoured.
  */
  expect(namedDesignerTeam({ chosen: [B, C], lead: A })).toEqual({ lead: B, team: [B, C] });
});

test("a lead with nothing ticked IS the team, because that is what an older draft looks like", () => {
  /*
    Not a hypothetical. A draft written before this control became a multi-select carries a lead and
    an empty list, and the rule above read over it would drop the designer the workshop was opened
    for — silently, a fortnight later, when the sync finally created it.
  */
  expect(namedDesignerTeam({ chosen: [], lead: A })).toEqual({ lead: A, team: [A] });
});

test("blanks are absent and duplicates collapse, and the ticked order is otherwise kept", () => {
  // The ticked order is the order the admin built and the only order they can see.
  expect(namedDesignerTeam({ chosen: ["", " ", C, A, C], lead: "" })).toEqual({ lead: C, team: [C, A] });
});

test("a padded id is trimmed on both sides of the comparison, so the lead still matches", () => {
  expect(namedDesignerTeam({ chosen: [` ${A} `, B], lead: ` ${A} ` })).toEqual({ lead: A, team: [A, B] });
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. What actually goes on the wire
 * ──────────────────────────────────────────────────────────────────────────── */

test("nobody named sends NEITHER key, which is the only shape an older API can answer", () => {
  /*
    `APIModel` is `extra="forbid"`, so a deployment predating either field answers 422
    `extra_forbidden` to a body that merely CARRIES an unknown key — and this repository ships the
    browser bundle and the API separately, so that skew is a live state. On the offline arm the same
    422 would strand a whole workshop, its 22 stages and its photographs behind a refusal the sync
    reads as permanent, because `saveOrQueue` will not queue a 4xx.
  */
  expect(designerCreateFields({ chosen: [], lead: "" })).toEqual({});
});

test("ONE designer sends the singular key alone — byte-identical to the body before this wave", () => {
  /*
    THE MOST IMPORTANT ROW IN THIS FILE. The overwhelmingly common create names one designer, and it
    must go on working against an API that has never heard of `designerUserIds`. Sending the plural
    key here — even as a one-element array — would make every ordinary create depend on the server
    having been rolled forward first.
  */
  expect(designerCreateFields({ chosen: [A], lead: "" })).toEqual({ designerUserId: A });
  expect(designerCreateFields({ chosen: [], lead: A })).toEqual({ designerUserId: A });
});

test("several designers send both keys, lead first", () => {
  expect(designerCreateFields({ chosen: [A, B], lead: B })).toEqual({
    designerUserId: B,
    designerUserIds: [B, A]
  });
});

test("an empty array is never sent: silence and 'the answer is none' are different sentences", () => {
  const body = designerCreateFields({ chosen: ["", "  "], lead: "" });
  expect(Object.prototype.hasOwnProperty.call(body, "designerUserIds")).toBe(false);
  expect(Object.prototype.hasOwnProperty.call(body, "designerUserId")).toBe(false);
});

test("the cap is the server's, not a number this client chose", () => {
  /*
    The create and `PUT /design-workshops/{id}/viewers` write the SAME table, and the server imports
    its create cap from `MAX_DESIGN_WORKSHOP_VIEWERS` rather than choosing a second one: a create
    that accepted a set the viewers screen would refuse is one list with two rules.
  */
  expect(MAX_NAMED_DESIGNERS).toBe(100);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The draft a workshop started with no signal lives in
 * ──────────────────────────────────────────────────────────────────────────── */

test("a brand-new draft carries an empty team and the current document version", () => {
  const draft = newLocalDraft({ title: "Bhujodi" }, { ownerUserId: "u1" });
  expect(draft.header.designerUserIds).toEqual([]);
  expect(draft.header.designerUserId).toBeNull();
  expect(draft.schemaVersion).toBe(DW_DRAFT_SCHEMA_VERSION);
});

test("a workshop started in a courtyard keeps the designers the admin picked before the signal went", () => {
  const draft = newLocalDraft(
    { title: "Bhujodi", designerUserId: B, designerUserIds: [B, A] },
    { ownerUserId: "u1" }
  );
  expect(draft.header.designerUserId).toBe(B);
  expect(draft.header.designerUserIds).toEqual([B, A]);
  // And the create arm of the sync sends exactly what the form would have sent online.
  expect(
    designerCreateFields({
      chosen: draft.header.designerUserIds,
      lead: draft.header.designerUserId ?? ""
    })
  ).toEqual({ designerUserId: B, designerUserIds: [B, A] });
});

test("adopting a stranded draft leaves the designers alone — it re-points, it does not re-decide", () => {
  /*
    `adoptedIntoWorkshop` clears `serverLoadedAt` and `removedFrom` (the two fields whose survival
    would let an adoption EMPTY the workshop it joined) and nothing about the header. The target
    workshop was created by an admin who already named its designers; the draft's own list is a
    create input for a create that will now never happen.
  */
  const draft = newLocalDraft(
    { title: "Bhujodi", designerUserId: A, designerUserIds: [A, B] },
    { ownerUserId: "u1" }
  );
  const moved: DwDraft = adoptedIntoWorkshop(draft, "srv-1");
  expect(moved.remoteId).toBe("srv-1");
  expect(moved.header.designerUserIds).toEqual([A, B]);
  expect(moved.header.designerUserId).toBe(A);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. The surfaces — presence checks against the exact source
 * ──────────────────────────────────────────────────────────────────────────── */

const root = join(__dirname, "..");

/**
 * Read with the newlines NORMALISED, and that is not tidiness — it is the trap this kind of
 * assertion actually falls into.
 *
 * This checkout is CRLF (`.gitattributes` sees to it), so every line of every file below ends
 * `\r\n`. A multi-line `toContain` written the way it reads on screen therefore matches NOTHING,
 * and the failure is silent in the worst direction: the test goes red about source that is
 * perfectly correct, and the obvious "fix" is to delete the assertion. Normalising here means an
 * expectation can be copied straight out of the file it is about.
 */
function sourceOf(...parts: string[]): string {
  return readFileSync(join(root, ...parts), "utf8").replace(/\r\n/g, "\n");
}

const picker = sourceOf("components", "designworkshop", "WorkshopDesignerPicker.tsx");
const page = sourceOf("app", "(protected)", "design-workshops", "page.tsx");
const wire = sourceOf("lib", "designWorkshops.ts");
const store = sourceOf("lib", "designWorkshopStore.ts");

test("the picker is a multi-select whose own filter is off, with a capHint naming the server box", () => {
  /*
    §11.5. `GET /design-workshops/eligible-viewers` answers at most 2000 accounts and that ceiling is
    reached on this repository, so a client-side filter would search only the part of the alphabet
    that fitted and answer "No matches" for a colleague who is eligible and merely sorts late. And
    `searchable={false}` does not switch the RENDER CAP off, so without a `capHint` the panel's own
    footer tells an admin to type into a filter box that is not on screen.
  */
  expect(picker).toContain("<MultiSelectDropdown");
  expect(picker).toContain("searchable={false}");
  expect(picker).toContain('capHint="Use the search box above to reach the rest');
  expect(picker).toContain("listEligibleDesignWorkshopViewers(term)");
  expect(picker).toContain("<SearchInput");
});

test("typing in the picker's search box does not arm the form's unsaved-changes prompt", () => {
  /*
    THE REVERSE OF THE THEMED-CONTROL TRAP. The picker sits inside the create form, which arms its
    prompt from the form's own `onInput`; a search box is a real text input, so without a firewall
    merely TYPING to look somebody up marks the form dirty and an admin who searched, ticked nothing
    and pressed Cancel cannot leave the page. Same device as `components/forms/WorkshopSelect`.
  */
  expect(picker).toContain("onInput={(event) => event.stopPropagation()}");
});

test("the lead is printed on screen, because a tick order is not a decision anybody can see", () => {
  // Rendered through the SAME function the submit uses, so the sentence and the body cannot
  // disagree about whose profile is copied into stage 1.
  expect(picker).toContain("namedDesignerTeam({ chosen: values, lead })");
  expect(picker).toContain("Stage 1, stage 3 and the report will carry");
});

test("the multi-select arms the dirty flag by hand, because a themed control fires no input event", () => {
  expect(page).toContain("onInput={markDirty}");
  expect(page).toContain("markDirty();\n              setDesignerUserIds(next);");
  expect(page).toContain("useLeaveGuard(dirty,");
});

test("the create sends what the picker resolved, through the one shared rule", () => {
  expect(page).toContain("namedDesignerTeam({ chosen: designerUserIds, lead: leadDesignerId })");
  expect(page).toContain("designerUserId: designers.lead || undefined");
  expect(page).toContain("designerUserIds: designers.team");
});

test("PATCH is closed to BOTH designer keys, or a header edit is refused whole", () => {
  /*
    `DesignWorkshopUpdate` is `extra="forbid"` and has neither member, so a PATCH carrying either is
    refused with `extra_forbidden` — the whole update, not the offending key. The `Omit` is what
    makes that closure visible in the type. It was already load-bearing for the singular field; the
    moment the plural one joined `DwCreateBody` it had to join this too.
  */
  expect(wire).toContain('Omit<Partial<DwCreateBody>, "designerUserId" | "designerUserIds">');
});

test("one normaliser decides the wire shape, and both create arms go through it", () => {
  // The form's own POST and the sync pass's — minutes or a fortnight later — must send the same
  // body for the same choice, and only `createDesignWorkshop` is on both paths.
  expect(wire).toContain("designerCreateFields({ chosen: designerUserIds ?? [], lead: designerUserId ?? \"\" })");
  expect(store).toContain("designerUserId: draft.header.designerUserId,\n              designerUserIds: draft.header.designerUserIds,");
});

test("the migration rung defaults the team to empty rather than seeding it from the lead", () => {
  /*
    A v3 document has no `designerUserIds` at all. Seeding it from `designerUserId` would say the
    same thing twice, and the day somebody unticks the lead in the picker the two copies would
    disagree about who is on the workshop — with the stale one being what the create actually sends.
    `designerCreateFields` already reads an empty list with a lead in it as "the lead alone is the
    team", which is the whole reason the rung can afford to be this small.
  */
  expect(store).toContain("export const DW_DRAFT_SCHEMA_VERSION = 4;");
  expect(store).toContain("      case 3:");
  expect(store).toContain("              : []");
});
