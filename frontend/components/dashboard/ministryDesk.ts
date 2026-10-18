import {
  Binoculars,
  CalendarRange,
  DraftingCompass,
  FileSearch,
  FileSignature,
  LayoutDashboard,
  Stamp,
  UserCheck,
  type LucideIcon
} from "lucide-react";

import {
  canApproveDesignWorkshops,
  canAssignWorkshopOversight,
  canInspectDesignWorkshops,
  canReadAnnualPlan,
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
 * sanctioned, staffed, filled in, read back, approved and handed on to the office.
 *
 * ── WHY AN OVERVIEW LEADS A LIFECYCLE ───────────────────────────────────────────────────────────
 *
 * The rows below the first are a SEQUENCE and are asserted as one against the directorate
 * walkthrough's deck. "Ministry dashboard" is not a step in that sequence — it is the register of
 * every workshop that has been through it, which is why it sits above rather than inside. An officer
 * opening this card most often wants to know what is happening before deciding which of the acts
 * below to perform, and a register filed last would be a register reached after scrolling past the
 * things it summarises. `DIRECTORATE_STEPS` carries the matching step at the same index and
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
 * `components/guide/directorateSteps.ts` teaches these screens in exactly this sequence, and
 * `e2e/ministry-desk-unit.spec.ts` holds the two lists to each other — same hrefs, same order. A
 * launcher and a lesson that disagree about the order of one job teach two jobs, and the reader has
 * no way to tell which is the product. It is also the cheapest guard against the defect this
 * repository keeps paying for: a screen added to one surface and forgotten on the other.
 *
 * ── THE LABELS ARE THE NAV'S, NOT NEW ONES ──────────────────────────────────────────────────────
 *
 * Read off `NAV_ITEMS` in `components/DynamicIslandNav.tsx`, and asserted against that file as text.
 * Every one of these destinations already answers to a name in the menu, and a sixth spelling
 * invented on a card is a name nobody's grep finds and nobody's colleague recognises. Apart from
 * Design workshops, none of these has an Android counterpart to be parity with — the nav's own comment says so where it
 * declares them — so the web nav is the only authority there is, and this table follows it rather
 * than competing with it.
 *
 * ── AND THERE IS NO DASHBOARD TILE FOR ANY OF THEM, WHICH IS WHY THE CARD EXISTS ────────────────
 *
 * The `tiles` array on the dashboard is held to Android's `EntryMode` list by two parity tests, one
 * per client, and none of these is an `EntryMode` — so none of them can join that grid without
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
    note: "Every workshop and each designer's progress",
    can: canSeeMinistryDashboard
  },
  {
    label: "Annual plan",
    href: "/annual-plan",
    icon: CalendarRange,
    note: "This year's planned workshops",
    can: canReadAnnualPlan
  },
  {
    label: "Sanction orders",
    href: "/sanction-orders",
    icon: FileSignature,
    note: "Record an order to open its workshop",
    can: canRecordSanctionOrders
  },
  {
    label: "Workshop oversight",
    href: "/officers",
    icon: UserCheck,
    note: "Name designers, officers and artisans",
    can: canAssignWorkshopOversight
  },
  {
    label: "Design workshops",
    href: "/design-workshops",
    icon: DraftingCompass,
    note: "The workshops you can open and fill in",
    can: canRunDesignWorkshops
  },
  {
    label: "Workshops I monitor",
    href: "/officers/monitored",
    icon: Binoculars,
    note: "Workshops you supervise",
    can: canReadWorkshopOversight
  },
  {
    /*
      THE INSPECTION A MINISTRY ADMIN OR AN ADMIN MAY BE NAMED TO (2026-10-18), before the sign-off it
      feeds. Its badge counts the reader's own handed-in inspections. An Assistant or Regional
      Director cannot hold an inspection, so `canInspectDesignWorkshops` keeps the row off their desk.
    */
    label: "Workshops to inspect",
    href: "/design-workshop-inspections",
    icon: FileSearch,
    note: "Reports handed in for your review",
    can: canInspectDesignWorkshops
  },
  {
    /*
      LAST, BECAUSE IT IS THE END OF THE SEQUENCE: the report has been filled in, inspected and read
      back, and the Ministry Admin signs it off — approves it or sends it back with a reason, and hands
      an approved report on to the office. The master admin's card carries it too; an Assistant or
      Regional Director's does not, because a director post is for reading a workshop and the sign-off
      is the ministry's (`canApproveDesignWorkshops`, the mirror of `require_approving_authority`).
    */
    label: "Reports to approve",
    href: "/design-workshop-approvals",
    icon: Stamp,
    note: "Approve or return handed-in reports",
    can: canApproveDesignWorkshops
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

/* ────────────────────────────────────────────────────────────────────────────
 * THE SECOND BADGE — how many reports are waiting for the Ministry Admin's approval.
 *
 * The same three things as the sanction badge above and for the same reasons: a destination that
 * names the row it rides on, a whole sentence for a pointer and a screen reader, and the one pill
 * class. The number is `components/hooks/useAwaitingApprovalCount.ts`, one store read by the nav and
 * by this card, and it counts the reports in Pre-submission — the ones nobody has decided on yet.
 * ──────────────────────────────────────────────────────────────────────────── */

/** The destination the approval count rides on — a row on this desk, held to it by the spec. */
export const AWAITING_APPROVAL_BADGE_HREF = "/design-workshop-approvals";

/** The whole sentence the approval badge is given, in both plurals. */
export function awaitingApprovalSentence(count: number): string {
  return count === 1 ? "1 report is waiting for approval" : `${count} reports are waiting for approval`;
}

/* THE THIRD BADGE (2026-10-18) — how many of the reader's own inspections are handed in and waiting
 * for them. The same pill and the same sentence shape; the number is
 * `components/hooks/useAwaitingInspectionCount.ts`, scoped by the reader's inspection rows. */

/** The destination the inspection count rides on — the nav entry "Workshops to inspect". */
export const AWAITING_INSPECTION_BADGE_HREF = "/design-workshop-inspections";

/** The whole sentence the inspection badge is given, in both plurals. */
export function awaitingInspectionSentence(count: number): string {
  return count === 1 ? "1 report is waiting for your review" : `${count} reports are waiting for your review`;
}

/* ────────────────────────────────────────────────────────────────────────────
 * THE PHONE SUMMARY — what the desk says while it is folded below `sm`.
 *
 * On a phone the full desk is about a screen and a half, and it sits above the reader's own work.
 * So below `sm` the card folds to its title, the counts that need someone to act, and a "Show all"
 * control; from `sm` up nothing changes. The judgement — which counts, in which order, and when the
 * card may say "Nothing waiting" — is here so `e2e/ministry-desk-collapse-unit.spec.ts` can call it.
 * ──────────────────────────────────────────────────────────────────────────── */

/** One count on the folded card: a number, the words beside it, and the row it opens. */
export type MinistryDeskCount = {
  href: string;
  count: number;
  /** The words after the number, plural-correct ("reports waiting for approval"). */
  label: string;
  /** The whole sentence for a screen reader — the badge's own, so the two never differ. */
  sentence: string;
};

/**
 * What the folded card shows.
 *
 * - `waiting`: at least one count is above zero; only those are listed, in desk order.
 * - `clear`: every count this desk carries has ANSWERED, and every one is zero.
 * - `unknown`: a count is still loading or failed, and none is above zero. The card then claims
 *   nothing — "Nothing waiting" over a count that never arrived would be the silent-emptiness bug.
 */
export type MinistryDeskSummary =
  | { kind: "waiting"; counts: MinistryDeskCount[] }
  | { kind: "clear" }
  | { kind: "unknown" };

/** Counts as the two stores report them: `null` is "not known", never zero. */
export type MinistryDeskCountValues = {
  awaitingSanction: number | null;
  awaitingApproval: number | null;
  /** Carried only by a desk that draws the inspection badge; absent leaves it out of the summary. */
  awaitingInspection?: number | null;
};

export function ministryDeskCountLabel(href: string, count: number): string {
  if (href === AWAITING_APPROVAL_BADGE_HREF) {
    return count === 1 ? "report waiting for approval" : "reports waiting for approval";
  }
  if (href === AWAITING_INSPECTION_BADGE_HREF) {
    return count === 1 ? "report waiting for your review" : "reports waiting for your review";
  }
  return count === 1 ? "sanction order awaiting details" : "sanction orders awaiting details";
}

export function ministryDeskSummary(
  open: readonly Pick<MinistryDestination, "href">[],
  values: MinistryDeskCountValues
): MinistryDeskSummary {
  const sources: { href: string; value: number | null; sentence: (count: number) => string }[] = [
    { href: AWAITING_SANCTION_BADGE_HREF, value: values.awaitingSanction, sentence: awaitingSanctionSentence },
    { href: AWAITING_APPROVAL_BADGE_HREF, value: values.awaitingApproval, sentence: awaitingApprovalSentence }
  ];
  if (values.awaitingInspection !== undefined) {
    sources.push({
      href: AWAITING_INSPECTION_BADGE_HREF,
      value: values.awaitingInspection,
      sentence: awaitingInspectionSentence
    });
  }
  const order = open.map((destination) => destination.href);
  const carried = sources
    .filter((source) => order.includes(source.href))
    .sort((a, b) => order.indexOf(a.href) - order.indexOf(b.href));

  const counts = carried
    .filter((source) => typeof source.value === "number" && source.value > 0)
    .map((source) => ({
      href: source.href,
      count: source.value as number,
      label: ministryDeskCountLabel(source.href, source.value as number),
      sentence: source.sentence(source.value as number)
    }));
  if (counts.length > 0) return { kind: "waiting", counts };
  if (carried.length > 0 && carried.every((source) => source.value === 0)) return { kind: "clear" };
  return { kind: "unknown" };
}

/** The per-browser memory of the reader's choice. Absent means folded. */
export const MINISTRY_DESK_EXPANDED_KEY = "field_repo_ministry_desk_expanded";

/** Read the stored choice. Never throws: blocked storage reads as folded. */
export function readMinistryDeskExpanded(storage: Pick<Storage, "getItem"> | null | undefined): boolean {
  try {
    return storage?.getItem(MINISTRY_DESK_EXPANDED_KEY) === "1";
  } catch {
    return false;
  }
}

/** Store the choice. Never throws: a browser that refuses storage simply forgets it. */
export function writeMinistryDeskExpanded(
  storage: Pick<Storage, "setItem"> | null | undefined,
  expanded: boolean
): void {
  try {
    storage?.setItem(MINISTRY_DESK_EXPANDED_KEY, expanded ? "1" : "0");
  } catch {
    /* forgotten, not fatal */
  }
}
