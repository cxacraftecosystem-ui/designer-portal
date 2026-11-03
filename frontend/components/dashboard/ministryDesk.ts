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

export type MinistryDestination = {
  label: string;
  href: string;
  icon: LucideIcon;
  note: string;
  can: (user: User | null | undefined) => boolean;
};

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
    label: "Workshops to inspect",
    href: "/design-workshop-inspections",
    icon: FileSearch,
    note: "Reports handed in for your review",
    can: canInspectDesignWorkshops
  },
  {
    label: "Reports to approve",
    href: "/design-workshop-approvals",
    icon: Stamp,
    note: "Approve or return handed-in reports",
    can: canApproveDesignWorkshops
  }
];

export function ministryDeskFor(user: User | null | undefined): MinistryDestination[] {
  return MINISTRY_DESK.filter((destination) => destination.can(user));
}

export const AWAITING_SANCTION_BADGE_HREF = "/sanction-orders";

export function awaitingSanctionSentence(count: number): string {
  return count === 1
    ? "1 sanction order is awaiting its designer's details"
    : `${count} sanction orders are awaiting their designers' details`;
}

export const AWAITING_SANCTION_BADGE_CLASS =
  "inline-flex shrink-0 items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-semibold text-amber-800";

export const AWAITING_APPROVAL_BADGE_HREF = "/design-workshop-approvals";

export function awaitingApprovalSentence(count: number): string {
  return count === 1 ? "1 report is waiting for approval" : `${count} reports are waiting for approval`;
}

export const AWAITING_INSPECTION_BADGE_HREF = "/design-workshop-inspections";

export function awaitingInspectionSentence(count: number): string {
  return count === 1 ? "1 report is waiting for your review" : `${count} reports are waiting for your review`;
}

export type MinistryDeskCount = {
  href: string;
  count: number;
  label: string;
  sentence: string;
};

export type MinistryDeskSummary =
  | { kind: "waiting"; counts: MinistryDeskCount[] }
  | { kind: "clear" }
  | { kind: "unknown" };

export type MinistryDeskCountValues = {
  awaitingSanction: number | null;
  awaitingApproval: number | null;
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

export const MINISTRY_DESK_EXPANDED_KEY = "field_repo_ministry_desk_expanded";

export function readMinistryDeskExpanded(storage: Pick<Storage, "getItem"> | null | undefined): boolean {
  try {
    return storage?.getItem(MINISTRY_DESK_EXPANDED_KEY) === "1";
  } catch {
    return false;
  }
}

export function writeMinistryDeskExpanded(
  storage: Pick<Storage, "setItem"> | null | undefined,
  expanded: boolean
): void {
  try {
    storage?.setItem(MINISTRY_DESK_EXPANDED_KEY, expanded ? "1" : "0");
  } catch {
  }
}
