import {
  Binoculars,
  CalendarRange,
  DraftingCompass,
  FileSignature,
  UserCheck,
  type LucideIcon
} from "lucide-react";

import {
  canAssignWorkshopOversight,
  canManageAnnualPlan,
  canReadWorkshopOversight,
  canRunDesignWorkshops
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
 * THE MINISTRY DESK, in the order a workshop reaches these screens: planned, sanctioned, staffed,
 * filled in, read back.
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
