/**
 * WHO MAY BE PUT ONTO A DESIGN WORKSHOP, AND WHO MAY DO THE PUTTING.
 *
 * The owner's ask was "a multi-select for designers on the design & prototype workshop, so a designer
 * can be taken onto that workshop list and thereby permitted to participate". Three lists in this
 * repository could plausibly answer that sentence and only one of them actually permits anything, so
 * these tests pin the choice and the gate rather than the pixels.
 *
 * ── THE THREE ROSTERS, AND WHY IT IS THE VIEWER GRANT ────────────────────────────────────────────
 *
 *   1. `DesignWorkshopViewer` — THE ONE THIS IS. `load_workshop_or_404` admits the creator, an admin,
 *      and the holder of one of these rows, and the same helper guards the stage WRITES. This row is
 *      the difference between a designer opening the workshop and being told it does not exist.
 *   2. The designer roster at /admin/designers — global, about who may sign in as a designer AT ALL.
 *      A name on it grants access to no workshop. It touches this feature at exactly one point: a
 *      suspended roster row drops somebody out of the eligible set.
 *   3. The stage-3 participant roster — `many("participant", "DwParticipant", "Participating
 *      artisans", …)` in `backend/app/services/stage_definitions.py`. ARTISANS, recorded as research
 *      data about who attended. It is the wrong answer that looks most like the right one, because
 *      it is the list literally called "participants"; writing a designer into it records a false
 *      fact about the fieldwork and confers nothing.
 *
 * ── WHY THESE ARE PREDICATE AND PRESENCE CHECKS AND NOT A RENDER ────────────────────────────────
 *
 * The panel's own behaviour (the replace-the-whole-set PUT, the creator shown outside the picker, the
 * server-side people search) is already covered by `design-workshop-viewers.spec.ts` and
 * `design-workshop-viewers-search.spec.ts` against the component where it lives. What is new and
 * untested is the MOUNT: that the list page grows the control, and that it is behind the right gate.
 * The source assertions below are deliberately all PRESENCE checks on exact JSX, which this
 * repository's prose comments cannot accidentally satisfy — see the long warning in
 * `qr-surfaces-unit.spec.ts` about why an absence check against raw text is unsafe here.
 *
 * ── TWO GATES, NOT ONE ──────────────────────────────────────────────────────────────────────────
 *
 * The role predicate is only half of it. `AdminViewProvider` documents `adminMode` as "True only
 * when the user has admin rights AND has admin view turned on. Gate admin UI on this", and its
 * default leaves every admin but the master admin out of admin view. /workshop-access/manage escapes
 * the toggle by being listed in ADMIN_CHROME_ROUTES — the whole page is admin chrome — but
 * /design-workshops is deliberately NOT in that list, because designers live on it, so the panel's
 * second mount has to carry the toggle itself.
 *
 * ── WHAT THIS FILE CANNOT DO, AND WHERE THAT IS DONE INSTEAD ────────────────────────────────────
 *
 * These are source and predicate assertions with no browser, so they cannot prove the gate is a real
 * hide rather than a hope. /design-workshops has no ROUTE_REDIRECTS rule and must never grow one, so
 * the gate here IS a client-side hide over a page a designer is entitled to — the shape
 * `design-workshop-viewers.spec.ts` warns has shipped twice in this repository. The navigation test
 * for it lives beside that warning, in `design-workshop-viewers.spec.ts` ("a designer keeps the whole
 * workshop list and never asks for a viewer list on it"), where the signed-in fixtures already are.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { test, expect } from "@playwright/test";

import { canCreateDesignWorkshops, canRunDesignWorkshops, isAdmin } from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

const PAGE = join(__dirname, "..", "app", "(protected)", "design-workshops", "page.tsx");
const source = readFileSync(PAGE, "utf8");

function user(role: UserRole): User {
  return { id: "u1", email: "someone@example.org", name: "Someone", role } as User;
}

test("administering who is on a workshop is admin-only, because every viewer route is", () => {
  /*
    All three routes in `backend/app/api/routes/design_workshop_viewers.py` are
    `Depends(require_admin)` — `GET /eligible-viewers`, `GET /{id}/viewers` and `PUT /{id}/viewers`,
    the two reads as well as the write. So a designer cannot even LIST who is on a workshop, and a
    panel rendered to one would be a form whose every request 401s.
  */
  expect(isAdmin(user("ADMIN"))).toBe(true);
  expect(isAdmin(user("MASTER_ADMIN"))).toBe(true);
  for (const role of ["DESIGNER", "INSPECTOR", "PROFESSOR", "RESEARCHER", "FIELD_CONTRIBUTOR", "CROWDSOURCE_VOLUNTEER"] as UserRole[]) {
    expect(`${role} may not administer viewers=${isAdmin(user(role))}`).toBe(`${role} may not administer viewers=false`);
  }
});

test("a designer is refused the access control and keeps everything else on the page", () => {
  /*
    THE FAILURE THIS PREVENTS is a narrowing applied one predicate too widely — the same trap
    `design-workshop-create-gate-unit.spec.ts` guards on the create control. A designer is refused
    the viewer administration AND must still be able to do the work of a workshop; gating the page,
    or the list, on `isAdmin` would cost them the fortnight of fieldwork the page exists to show.
  */
  expect(isAdmin(user("DESIGNER"))).toBe(false);
  expect(canRunDesignWorkshops(user("DESIGNER"))).toBe(true);

  // And the two admin-tier predicates are kept apart even though their sets are identical today.
  // They answer different questions — "may this account start a workshop" and "may it administer
  // access to one" — and the day either moves the other must not move with it silently.
  expect(canCreateDesignWorkshops(user("DESIGNER"))).toBe(false);
  expect(isAdmin(user("ADMIN")) && canCreateDesignWorkshops(user("ADMIN"))).toBe(true);
});

test("the design workshop list mounts the existing picker rather than growing a second one", () => {
  // MOUNTED, NOT REBUILT. A second picker over `PUT /{id}/viewers` would have to re-derive the four
  // things the existing panel already gets right — the whole-set replace, the creator outside the
  // picker, the ineligible-but-current viewer staying ticked, and the server-side search past the
  // 2000-row cap — and the last of those is invisible when it is wrong.
  expect(source).toContain('import { DesignWorkshopViewersPanel } from "@/components/settings/DesignWorkshopViewersPanel";');

  /*
    ONE ASSERTION THAT SPANS THE GATE AND THE MOUNT, not two independent substrings.

    This used to be `toContain("{isAdmin(user) ? (")` beside `toContain("<DesignWorkshopViewersPanel
    …/>")`, which proves only that both strings occur SOMEWHERE in a 1,100-line file. Moving the
    panel out of the conditional would have kept it green so long as any other admin block remained,
    and this page is a pure client-side hide over a route designers must be able to reach — there is
    no ROUTE_REDIRECTS rule underneath to catch the mistake.

    The regex reads: the gate opens, and the panel appears before anything closes it. `(?!\) : null\})`
    forbids the conditional's own closing form from occurring in between, which is exactly what
    "inside this block" means here. `\r?\n` because this repository's working copies are checked out
    with CRLF on Windows — a bare `\n` made this assertion fail against a file it should have passed.
  */
  expect(
    /\{isAdmin\(user\) && adminMode \? \(\r?\n(?:(?!\) : null\})[\s\S])*?<DesignWorkshopViewersPanel refreshToken=\{viewersRefresh\} \/>/.test(
      source
    ),
    "DesignWorkshopViewersPanel must be inside the `isAdmin(user) && adminMode` conditional"
  ).toBe(true);

  // And nowhere else. A second mount outside the gate would satisfy the regex above and still be a
  // panel rendered to a designer.
  expect(source.match(/<DesignWorkshopViewersPanel/g)?.length).toBe(1);
});

test("the viewers panel respects admin view, exactly as the delete control on this page does", () => {
  /*
    `AdminViewProvider` states it on the field itself — "True only when the user has admin rights AND
    has admin view turned on. Gate admin UI on this" — and its default leaves every admin but the
    master admin OUT of admin view. So gating on `isAdmin` alone handed a plain ADMIN with untouched
    settings a full workshop-administration panel on the designer-facing list, while the Delete
    control on the same page was correctly hidden from them.

    The panel's own docblock reasons it needs no such gate because /workshop-access/manage is in
    ADMIN_CHROME_ROUTES. That is a route which IS admin chrome; /design-workshops is deliberately not
    in that list, because designers live on it, so the exemption does not travel with the component.
  */
  expect(source).toContain("{isAdmin(user) && adminMode ? (");
  // The same shape as the older admin affordance further down the file, so the two cannot drift.
  expect(source).toContain("{allowDelete && adminMode ? (");
  // `isAdmin` alone must not be what gates a rendered admin surface here. (`allowDelete` is derived
  // from it at the top of the file and then ANDed with the toggle, which is the correct shape.)
  expect(source).not.toContain("{isAdmin(user) ? (");
});

test("the workshop's shareable code is offered from the list, for every row, under the row", () => {
  /*
    The QR half of the same feature: one person creates the workshop and the others need to reach
    THAT one. The card is mounted on the list because that is where somebody standing in a room finds
    the workshop to hold up — and it is offered on every row INCLUDING a device-local one, where
    `encodeWorkshopCode` renders its device-local refusal instead of a symbol. Hiding the control
    there would read as a missing feature rather than as a workshop that has not been shared yet.

    IT RENDERS IN AN EXPANDED ROW, `app/(protected)/media/page.tsx`'s pattern and the only one that
    is right for a card toggled from a ROW. The first version drew it above the search box: pressing
    "Show code" on row 12 of a 25-row list moved nothing the reader could see, because the card
    appeared several screens up. The assertion below is on the card being fed the ROW's own workshop
    rather than a page-level `codeFor` object, which is the difference in one line.
  */
  expect(source).toContain('<RecordCodeCard recordType="designWorkshop" id={workshop.id} title={workshop.title} />');
  expect(source).toContain('<td className="px-4 py-3" colSpan={6}>');
  // The row control that opens it, and the fact that it toggles rather than stacking cards.
  expect(source).toContain('{codeFor?.id === workshop.id ? "Hide code" : "Show code"}');

  // AND THE CARD CANNOT OUTLIVE ITS ROW. Paging, filtering or a refetch that drops the workshop must
  // take the state with it, or returning to that page silently re-opens a code nobody asked for.
  expect(source).toContain("if (codeFor && !rows.some((row) => row.id === codeFor.id)) setCodeFor(null);");
});
