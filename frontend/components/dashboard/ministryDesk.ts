import {
  Binoculars,
  CalendarRange,
  DraftingCompass,
  FileSignature,
  LayoutDashboard,
  UserCheck,
  type LucideIcon
} from "lucide-react";

import {
  canAssignWorkshopOversight,
  canManageAnnualPlan,
  canReadWorkshopOversight,
  canRunDesignWorkshops,
  canSeeMinistryDashboard
} from "@/lib/permissions";
import { canRecordSanctionOrders } from "@/lib/sanctionOrders";
import type { User } from "@/lib/types";

/**
 * One destination on the ministry desk.
 *
 * `can` IS THE DESTINATION'S OWN PREDICATE AND NOTHING ELSE — never a copy of one, and never a rank
 * that happens to agree with one today. Each is imported from the module that defines it, which is
 * the same module that route's `ROUTE_GUARDS` row imports it from, so a row on this card cannot
 * drift from the guard it stands in front of: change the gate and the card changes with it, in the
 * same edit, with no second list to remember. `e2e/ministry-desk-unit.spec.ts` asserts the stronger
 * form of that directly — for every tier on the ladder, a row is offered exactly when
 * `canAccessRoute` would admit its href.
 */
export type MinistryDestination = {
  /** The nav entry's label, character for character. See the note on naming below. */
  label: string;
  href: string;
  icon: LucideIcon;
  /** One line: what the screen is for. Not a description of the gate — the walkthrough does that. */
  note: string;
  can: (user: User | null | undefined) => boolean;
};

/**
 * THE MINISTRY DESK: the overview first, then the order a workshop reaches these screens — planned,
 * sanctioned, staffed, filled in, read back.
 *
 * ── WHY AN OVERVIEW LEADS A LIFECYCLE ───────────────────────────────────────────────────────────
 *
 * The rows below the first are a SEQUENCE and are asserted as one against the directorate
 * walkthrough's deck. "Ministry dashboard" is not a step in that sequence — it is the register of
 * every workshop that has been through it, which is why it sits above rather than inside. An officer
 * opening this card most often wants to know what is happening before deciding which of the five
 * acts to perform, and a register filed last would be a register reached after scrolling past the
 * five things it summarises. `DIRECTORATE_STEPS` carries the matching step at the same index and
 * `e2e/ministry-desk-unit.spec.ts` holds the two lists to each other, so moving this row obliges
 * moving that step in the same edit.
 *
 * ── A PURE MODULE BESIDE THE CARD, WHICH IS THIS REPOSITORY'S OWN SPLIT ─────────────────────────
 *
 * `components/admin/deletedWorkshops.ts` beside `DeletedWorkshopsCard.tsx`, `components/guide/steps.ts`
 * beside the guide's components, `components/ui/selectFilter.ts` beside the dropdowns. The reason is
 * always the same and it is the reason here: there is no React renderer in devDependencies, so a
 * judgement written inside JSX is only ever exercised by somebody looking at a screen. This table is
 * the judgement — which screens, in which order, behind which predicate — and it is worth a test.
 *
 * ── THE ORDER IS THE WALKTHROUGH'S, DELIBERATELY AND BY ASSERTION ───────────────────────────────
 *
 * `components/guide/directorateSteps.ts` teaches these five screens in exactly this sequence, and
 * `e2e/ministry-desk-unit.spec.ts` holds the two lists to each other — same hrefs, same order. A
 * launcher and a lesson that disagree about the order of one job teach two jobs, and the reader has
 * no way to tell which is the product. It is also the cheapest guard against the defect this
 * repository keeps paying for: a screen added to one surface and forgotten on the other.
 *
 * ── THE LABELS ARE THE NAV'S, NOT NEW ONES ──────────────────────────────────────────────────────
 *
 * Read off `NAV_ITEMS` in `components/DynamicIslandNav.tsx`, and asserted against that file as text.
 * Every one of these destinations already answers to a name in the menu, and a sixth spelling
 * invented on a card is a name nobody's grep finds and nobody's colleague recognises. Four of the
 * five have no Android counterpart to be parity with — the nav's own comment says so where it
 * declares them — so the web nav is the only authority there is, and this table follows it rather
 * than competing with it.
 *
 * ── AND THERE IS NO DASHBOARD TILE FOR ANY OF THEM, WHICH IS WHY THE CARD EXISTS ────────────────
 *
 * The `tiles` array on the dashboard is held to Android's `EntryMode` list by two parity tests, one
 * per client, and none of these five is an `EntryMode` — so none of them can join that grid without
 * putting the web out of step with the handset. A ministry officer's dashboard therefore showed them
 * nineteen tiles of a designer's work and not one of their own, and their screens were reachable
 * only from the nav sheet: behind a tap, in one scrolling column, where somebody who does not
 * already know a feature exists has no reason to go looking for it. That is the failure
 * `e2e/feature-entry-points.spec.ts` opens by naming — a feature a user cannot find is a feature
 * that was not built — and this card answers it without touching the parity-checked grid.
 */
export const MINISTRY_DESK: readonly MinistryDestination[] = [
  {
    label: "Ministry dashboard",
    href: "/ministry-dashboard",
    icon: LayoutDashboard,
    note: "Every workshop on the platform — ongoing, completed and newly registered — with each designer's progress and the lists to download",
    can: canSeeMinistryDashboard
  },
  {
    label: "Annual plan",
    href: "/annual-plan",
    icon: CalendarRange,
    note: "The ministry's directory of what is planned this year, and the row you open a workshop from",
    can: canManageAnnualPlan
  },
  {
    label: "Sanction orders",
    href: "/sanction-orders",
    icon: FileSignature,
    note: "Record an order: it opens the workshop, creates the designer's account and issues their sign-in link",
    can: canRecordSanctionOrders
  },
  {
    label: "Workshop oversight",
    href: "/officers",
    icon: UserCheck,
    note: "Name a workshop's designer and its two supervising officers, and upload its artisan list",
    can: canAssignWorkshopOversight
  },
  {
    label: "Design workshops",
    href: "/design-workshops",
    icon: DraftingCompass,
    note: "The workshops you may open — and, since you are in the workshop set, write in",
    can: canRunDesignWorkshops
  },
  {
    label: "Workshops I monitor",
    href: "/officers/monitored",
    icon: Binoculars,
    note: "The workshops you were named on as Assistant Director or Regional Director, read-only",
    can: canReadWorkshopOversight
  }
];

/**
 * The rows this account is actually offered.
 *
 * Pure, and exported, so the emptiness question below can be asked by a test rather than by opening a
 * browser as four different people.
 */
export function ministryDeskFor(user: User | null | undefined): MinistryDestination[] {
  return MINISTRY_DESK.filter((destination) => destination.can(user));
}

/* ────────────────────────────────────────────────────────────────────────────
 * THE BADGE — how many sanctioned workshops are stalled on their designer.
 *
 * The three things below are the pure half of a pill that is drawn by TWO components in two files:
 * the row on this card, and the "Sanction orders" entry in `components/DynamicIslandNav.tsx` (twice
 * over — the desktop dropdown and the sheet). The number itself comes from
 * `components/hooks/useAwaitingSanctionCount.ts`, one module-level store shared by both, so the two
 * surfaces cannot answer differently.
 *
 * They live HERE, in the pure module beside the card, for the reason the header gives and the reason
 * `components/tasks/openTaskCount.ts` exists: there is no React renderer in devDependencies, so a
 * plural rule written inside JSX is only ever exercised by somebody looking at a screen with exactly
 * one stalled order in the database. `e2e/ministry-desk-unit.spec.ts` calls the function directly.
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The destination the count rides on.
 *
 * A constant rather than a literal in five places (the card's row test and the card's `enabled`
 * test, the nav's dropdown, the nav's sheet, and the nav's `enabled` test), so the badge and the
 * fetch that feeds it can only ever be about one destination — the rule `PENDING_ACCESS_BADGE_HREF`
 * and `OPEN_TASK_BADGE_HREF` both follow.
 *
 * Written out rather than derived as `MINISTRY_DESK[1].href`: an index into that array is a silent
 * lie the day a row is inserted above it. The spec holds the two together instead — this must name
 * a row on the desk, and that row's predicate must BE `canRecordSanctionOrders`, which is what makes
 * "the row is on screen" a sound stand-in for "this account may read the count".
 */
export const AWAITING_SANCTION_BADGE_HREF = "/sanction-orders";

/**
 * The whole sentence a pointer and a screen reader are given.
 *
 * NOT A BARE DIGIT — `PendingAccessBadge`'s rule, and the reader who most needs this one is the
 * officer who has never seen it before. One sentence rather than a visible fragment plus an
 * `sr-only` tail, so the plural rule exists once, in a function a test can call.
 *
 * "Awaiting" is the server's word, not a new one: `GET /sanction-orders/awaiting-count` scores it,
 * and the register's own per-row pill says "Awaiting designer details" in
 * `readinessSentence`. The badge answers "how many" and that pill answers "which" — the endpoint's
 * docstring divides the two exactly that way — so they must not drift into two vocabularies for one
 * fact.
 *
 * ⚠ IT DOES NOT SAY "YOURS", AND MUST NOT. `awaiting_count()` takes no officer argument and counts
 * the whole register on purpose — its own docstring says so, and says why: the badge answers "how
 * much of this office's work is stalled", while `mine=true` on the LIST is where one officer narrows
 * it to their own. The register opens unnarrowed, so pressing the badge lands on the population it
 * counted. A sentence here claiming the orders were the reader's would be the exact failure that
 * docstring names — "3 waiting" opening onto nothing.
 */
export function awaitingSanctionSentence(count: number): string {
  return count === 1
    ? "1 sanction order is awaiting its designer's details"
    : `${count} sanction orders are awaiting their designers' details`;
}

/**
 * The pill itself, as one literal class string used by both renderers.
 *
 * ── WHY AMBER AND NOT THE MINISTRY ACCENT ───────────────────────────────────────────────────────
 *
 * This count is the register's readiness pill added up, and that pill is `bg-amber-100
 * text-amber-800` on `/sanction-orders` itself: one fact, one colour, on the badge and on the rows
 * it resolves to. It is also the palette's established word for this state — `StatusBadge`'s own
 * comment calls amber *"this palette's 'attention, not yet done with'"* and paints PENDING and
 * COMPLETE in exactly this pair — which is what an awaiting sanction is. And it is not the reader's
 * own work, so `OpenTaskBadge`'s purple would claim the officer can finish it by pressing the entry;
 * the thirteen fields are the designer's and the officer's move is to chase them.
 *
 * And it is NOT a ministry rung, deliberately, though it rides on a ministry destination. Measured
 * against this repository's own hexes: `ministry-700` and `amber-800` are ΔE 0.0043 in OKLab — the
 * same colour — and `ministry-100` and `amber-100` are 0.049, which is still inside "cannot tell
 * them apart". The place that matters is the nav SHEET, which is one flat list of every destination
 * rather than the grouped dropdowns: on a master admin's sheet the access queue's amber "waiting to
 * be approved to sign in" pill and this one are in the same column, and they mean entirely different
 * things. The arithmetic is in `tailwind.config.ts` beside the ramp.
 *
 * Both rungs are literal brand colours that do not invert, exactly as the two badges beside it are,
 * so no `dark:` pair is owed here — unlike every ministry-ramp site on this card, where one is.
 *
 * A shared constant rather than a shared component because the two renderers are in two files and
 * one of them is the nav, whose badges are local components by its own convention. What must not
 * drift is the pixels and the sentence; those are both here.
 */
export const AWAITING_SANCTION_BADGE_CLASS =
  "inline-flex shrink-0 items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-semibold text-amber-800";

/**
 * THE HOP THIS JOURNEY DOES NOT HAVE YET, said on the card rather than discovered by hunting for a
 * button.
 *
 * The five rows above are "in the order a workshop reaches them", and a reader is entitled to read
 * an ordered sequence as a complete one. It is not: the ministry's own terminus — read the report
 * back and sign it off — has no router. **VERIFIED** at the time of writing: there is no
 * `backend/app/api/routes/design_workshop_approvals.py`, and `DECISION_EDGES` in
 * `backend/app/schemas/design_workshop_review_loop.py` names the three verbs
 * (`/approve`, `/revise`, `/hand-on`) against a router "this workstream does not build", with the
 * words "until it lands APPROVED is simply unreachable". `PATCH /design-workshops/{id}` refuses all
 * three edges by design, so no header edit can manufacture one either.
 *
 * ── WHY IT IS ON SCREEN AND NOT ONLY IN A COMMENT ───────────────────────────────────────────────
 *
 * This card is the ministry account's front door and the only place in the product that lays their
 * screens out as a sequence. An officer who reads that sequence, watches a report reach
 * Pre-submission and then cannot find the button that signs it off has been taught that the product
 * is broken — the same failure `/design-workshops`' empty-scope screen already costs the directorate
 * tiers, and this repository's most repeated bug class in one sentence. Stating a missing step is
 * the cheapest possible fix and it is the truncation rule's own reasoning: work that is not done has
 * to be visible as not done.
 *
 * ── IT PROMISES NOTHING, ON PURPOSE ─────────────────────────────────────────────────────────────
 *
 * No release number and no "coming soon". Who the sanctioning authority is — which tier may approve
 * at all — is an open product question and was deferred rather than answered, so copy naming a tier
 * or a date would be this card inventing the answer. What is stated is only what is true today, and
 * what an officer can still do instead.
 *
 * ── IT IS MEANT TO BE DELETED ───────────────────────────────────────────────────────────────────
 *
 * `e2e/ministry-desk-unit.spec.ts` tests this constant against the ABSENCE of that router file: the
 * day the approvals workstream lands, that test goes red and this sentence — and the paragraph that
 * renders it — must come out in the same commit. A "not built yet" notice that outlives the thing
 * not being built is worse than never having written one.
 */
export const MINISTRY_APPROVAL_GAP =
  "Reading a report back is where this sequence stops today. Nothing in the product can approve a " +
  "finished workshop yet — approving one, withdrawing an approval and handing one on to the office " +
  "have no screen and no endpoint, and a header edit is refused — so none of the rows above is " +
  "waiting on a signature from you. Sending a report back for corrections does exist; it is the " +
  "inspector's, on the workshop itself.";
