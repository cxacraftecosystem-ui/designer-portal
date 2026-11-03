"use client";

import { ToggleSwitch } from "@/components/ui/Toggle";
import { NotificationBell } from "@/components/notifications/NotificationBell";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion, useMotionValueEvent, useScroll } from "framer-motion";
import {
  Activity,
  BadgeCheck,
  Bell,
  Binoculars,
  Boxes,
  Brush,
  CalendarRange,
  ChartNoAxesCombined,
  ClipboardCheck,
  ClipboardList,
  Compass,
  DraftingCompass,
  Eye,
  EyeOff,
  FileArchive,
  FileSearch,
  FileSignature,
  FileSpreadsheet,
  FolderTree,
  Gauge,
  GitBranch,
  IdCard,
  Image as ImageIcon,
  KeyRound,
  Layers,
  LayoutDashboard,
  LogOut,
  MapPinned,
  Menu as MenuIcon,
  MessageSquare,
  PencilRuler,
  QrCode,
  Search,
  Settings as SettingsIcon,
  Share2,
  SlidersHorizontal,
  Stamp,
  Star,
  UserCheck,
  UserCog,
  Users,
  Wrench,
  X,
  type LucideIcon
} from "lucide-react";

import { useAdminView } from "@/components/AdminViewProvider";
import { AppSearchHeader } from "@/components/nav/AppSearchHeader";
import { AppSearchPanel } from "@/components/nav/AppSearchPanel";
import { BottomIslandNav } from "@/components/nav/BottomIslandNav";
import { useAuth } from "@/components/AuthProvider";
import {
  AWAITING_APPROVAL_BADGE_HREF,
  AWAITING_INSPECTION_BADGE_HREF,
  AWAITING_SANCTION_BADGE_CLASS,
  AWAITING_SANCTION_BADGE_HREF,
  awaitingApprovalSentence,
  awaitingInspectionSentence,
  awaitingSanctionSentence
} from "@/components/dashboard/ministryDesk";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { useAwaitingApprovalCount } from "@/components/hooks/useAwaitingApprovalCount";
import { useAwaitingInspectionCount } from "@/components/hooks/useAwaitingInspectionCount";
import { useAwaitingSanctionCount } from "@/components/hooks/useAwaitingSanctionCount";
import { useOpenTaskCount } from "@/components/hooks/useOpenTaskCount";
import { useUnreadNotificationCount } from "@/components/hooks/useUnreadNotifications";
import { usePendingAccessCount } from "@/components/hooks/usePendingAccessCount";
import { OPEN_TASK_BADGE_HREF, openTaskBadgeSentence } from "@/components/tasks/openTaskCount";
import { HoveredLink, MenuItem } from "@/components/ui/navbar-menu";
import { WorkshopLogo } from "@/components/WorkshopLogo";
import { NOTIFICATIONS_HREF } from "@/lib/inAppNotifications";
import { ministryExportsEnabled } from "@/lib/ministryExports";
import { canRecordSanctionOrders } from "@/lib/sanctionOrders";
import {
  canApproveDesignWorkshops,
  canAssignWorkshopOversight,
  canManageMinistryExports,
  canCreateRecords,
  canDownloadDataset,
  canInspectDesignWorkshops,
  canManageAccessRoster,
  canReadAnnualPlan,
  canManageCrafts,
  canManageDesignerRoster,
  canManageUsers,
  canManageWorkshops,
  canReadWorkshopOversight,
  canReview,
  canRunDesignWorkshops,
  canSeeMinistryDashboard,
  isAdmin,
  roleLabel
} from "@/lib/permissions";
import { lockPageScroll, unlockPageScroll } from "@/lib/scrollLock";
import { cn } from "@/lib/utils";
import type { User } from "@/lib/types";

export const NAV_GROUPS = ["Record", "Browse", "Admin", "Account"] as const;
type NavGroup = (typeof NAV_GROUPS)[number];

const PENDING_ACCESS_BADGE_HREF = "/admin";

function PendingAccessBadge({ count }: { count: number }) {
  if (count <= 0) return null;
  const people = count === 1 ? "person is" : "people are";
  return (
    <span
      title={`${count} ${people} waiting to be approved to sign in`}
      className="ml-auto inline-flex shrink-0 items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-semibold text-amber-800"
    >
      {count}
      <span className="font-medium">waiting</span>
      <span className="sr-only">to be approved to sign in</span>
    </span>
  );
}

function OpenTaskBadge({ count }: { count: number }) {
  if (count <= 0) return null;
  const sentence = openTaskBadgeSentence(count);
  return (
    <span
      title={sentence}
      className="ml-auto inline-flex shrink-0 items-center gap-1 rounded-full bg-purple-100 px-2 py-0.5 text-[11px] font-semibold text-purple-800"
    >
      <span aria-hidden>{count}</span>
      <span aria-hidden className="font-medium">
        open
      </span>
      <span className="sr-only">{sentence}</span>
    </span>
  );
}

function AwaitingSanctionBadge({ count }: { count: number }) {
  if (count <= 0) return null;
  const sentence = awaitingSanctionSentence(count);
  return (
    <span title={sentence} className={`ml-auto ${AWAITING_SANCTION_BADGE_CLASS}`}>
      <span aria-hidden>{count}</span>
      <span aria-hidden className="font-medium">
        awaiting
      </span>
      <span className="sr-only">{sentence}</span>
    </span>
  );
}

function AwaitingApprovalBadge({ count }: { count: number }) {
  if (count <= 0) return null;
  const sentence = awaitingApprovalSentence(count);
  return (
    <span title={sentence} className={`ml-auto ${AWAITING_SANCTION_BADGE_CLASS}`}>
      <span aria-hidden>{count}</span>
      <span aria-hidden className="font-medium">
        waiting
      </span>
      <span className="sr-only">{sentence}</span>
    </span>
  );
}

function AwaitingInspectionBadge({ count }: { count: number }) {
  if (count <= 0) return null;
  const sentence = awaitingInspectionSentence(count);
  return (
    <span title={sentence} className={`ml-auto ${AWAITING_SANCTION_BADGE_CLASS}`}>
      <span aria-hidden>{count}</span>
      <span aria-hidden className="font-medium">
        to review
      </span>
      <span className="sr-only">{sentence}</span>
    </span>
  );
}

export type NavItem = {
  href: string;
  label: string;
  icon: LucideIcon;
  group: NavGroup | null;
  can: (user: User) => boolean;
  gate: string;
  adminSurface?: boolean;
};

const everyone = () => true;

export const NAV_ITEMS: NavItem[] = [
  { href: "/dashboard", label: "Dashboard", icon: Gauge, group: null, can: everyone, gate: "get_current_user" },
  { href: "/guide", label: "Walkthrough", icon: Compass, group: null, can: everyone, gate: "none (static page)" },

  { href: "/artisans", label: "Record artisan", icon: Users, group: "Record", can: canCreateRecords, gate: "require_record_creator" },
  { href: "/products", label: "Record product", icon: Boxes, group: "Record", can: canCreateRecords, gate: "require_record_creator" },
  { href: "/processes", label: "Document process", icon: GitBranch, group: "Record", can: canCreateRecords, gate: "require_record_creator" },
  { href: "/tools", label: "Record tool", icon: Wrench, group: "Record", can: canCreateRecords, gate: "require_record_creator" },
  { href: "/questionnaire", label: "Take interview", icon: ClipboardList, group: "Record", can: everyone, gate: "get_current_user" },
  { href: "/media", label: "Upload media", icon: ImageIcon, group: "Record", can: everyone, gate: "get_current_user" },
  { href: "/crafts", label: "Add craft", icon: Brush, group: "Record", can: canManageCrafts, gate: "require_craft_manager" },
  { href: "/workshops", label: "Record workshop", icon: Users, group: "Record", can: canManageWorkshops, gate: "require_workshop_manager" },
  {
    href: "/design-workshops",
    label: "Design workshops",
    icon: DraftingCompass,
    group: "Record",
    can: canRunDesignWorkshops,
    gate: "get_current_user on the list; _require_designer on every write (narrowed here to can_run_design_workshops)"
  },
  {
    href: "/sanction-orders",
    label: "Sanction orders",
    icon: FileSignature,
    group: "Record",
    can: canRecordSanctionOrders,
    gate: "require_sanction_recorder"
  },
  {
    href: "/questionnaires",
    label: "My questionnaires",
    icon: FileSpreadsheet,
    group: "Record",
    can: canRunDesignWorkshops,
    gate: "can_run_design_workshops (_require_designer)"
  },

  { href: "/activity", label: "My Activity", icon: Activity, group: "Browse", can: everyone, gate: "get_current_user" },
  { href: "/tasks", label: "Tasks", icon: ClipboardCheck, group: "Browse", can: everyone, gate: "get_current_user" },
  { href: "/search", label: "Search", icon: Search, group: "Browse", can: everyone, gate: "get_current_user" },
  { href: "/scan", label: "Scan a code", icon: QrCode, group: "Browse", can: everyone, gate: "get_current_user" },
  { href: "/map", label: "Map", icon: MapPinned, group: "Browse", can: everyone, gate: "get_current_user" },
  { href: "/data", label: "View Data", icon: FolderTree, group: "Browse", can: canDownloadDataset, gate: "require_dataset_downloader" },
  {
    href: "/questionnaire/coverage",
    label: "Questionnaire coverage",
    icon: Layers,
    group: "Browse",
    can: everyone,
    gate: "get_current_user"
  },
  { href: "/sharing", label: "Share data access", icon: Share2, group: "Browse", can: everyone, gate: "get_current_user" },
  {
    href: "/design-review",
    label: "Design review",
    icon: Star,
    group: "Browse",
    can: canRunDesignWorkshops,
    gate: "can_run_design_workshops (load_ratable_workshop_or_404 on GET /design-ratings/rounds/{round})"
  },
  {
    href: "/sketches-and-prototypes",
    label: "Sketches & prototypes",
    icon: PencilRuler,
    group: "Browse",
    can: canRunDesignWorkshops,
    gate: "can_run_design_workshops (load_workshop_or_404 on the chosen workshop; get_current_user + visible_to_clause on the picker's list)"
  },
  {
    href: "/design-workshop-inspections",
    label: "Workshops to inspect",
    icon: FileSearch,
    group: "Browse",
    can: canInspectDesignWorkshops,
    gate: "assert_inspection_surface (INSPECTION_HOLDER_ROLES, services/design_workshop_inspectors.py)"
  },

  {
    href: "/ministry-dashboard",
    label: "Ministry dashboard",
    icon: LayoutDashboard,
    group: "Browse",
    can: canSeeMinistryDashboard,
    gate: "require_ministry_dashboard_reader (MINISTRY_DASHBOARD_ROLES, app/api/routes/ministry_dashboard.py)"
  },
  {
    href: "/officers",
    label: "Workshop oversight",
    icon: UserCheck,
    group: "Browse",
    can: canAssignWorkshopOversight,
    gate: "assert_may_assign_oversight (OVERSIGHT_ASSIGNER_ROLES, services/design_workshop_oversight.py)"
  },
  {
    href: "/ministry-exports",
    label: "Ministry exports",
    icon: FileArchive,
    group: "Browse",
    can: (user) => canManageMinistryExports(user) && ministryExportsEnabled(user),
    gate: "require_ministry_export_manager (OVERSIGHT_ASSIGNER_ROLES, app/api/routes/ministry_exports.py)"
  },
  {
    href: "/officers/monitored",
    label: "Workshops I monitor",
    icon: Binoculars,
    group: "Browse",
    can: canReadWorkshopOversight,
    gate: "assert_oversight_surface (OVERSIGHT_HOLDER_ROLES, services/design_workshop_oversight.py)"
  },
  {
    href: "/design-workshop-approvals",
    label: "Reports to approve",
    icon: Stamp,
    group: "Browse",
    can: canApproveDesignWorkshops,
    gate: "require_approving_authority (APPROVAL_AUTHORITY_ROLES, core/deps.py)"
  },
  { href: "/tools?assign=1", label: "Assign tools to artisans", icon: Wrench, group: "Browse", can: canCreateRecords, gate: "get_current_user + owner/EDIT-grant/admin per artisan" },

  { href: "/review", label: "Review", icon: Eye, group: "Browse", can: canReview, gate: "require_reviewer" },
  { href: "/admin", label: "Settings hub", icon: SlidersHorizontal, group: "Admin", can: isAdmin, gate: "require_admin", adminSurface: true },
  { href: "/admin/analytics", label: "Cross-workshop analytics", icon: ChartNoAxesCombined, group: "Admin", can: isAdmin, gate: "require_admin", adminSurface: true },
  { href: "/users", label: "Manage users", icon: UserCog, group: "Admin", can: canManageUsers, gate: "require_professor", adminSurface: true },
  {
    href: "/annual-plan",
    label: "Annual plan",
    icon: CalendarRange,
    group: "Admin",
    can: canReadAnnualPlan,
    gate: "require_annual_plan_manager"
  },

  {
    href: "/designers/profile",
    label: "My designer profile",
    icon: IdCard,
    group: "Account",
    can: canRunDesignWorkshops,
    gate: "get_current_user (narrowed here to can_run_design_workshops)"
  },
  { href: "/notifications", label: "Notifications", icon: Bell, group: "Account", can: everyone, gate: "get_current_user (GET /notifications)" },
  { href: "/settings", label: "Settings", icon: SettingsIcon, group: "Account", can: everyone, gate: "get_current_user (PUT /preferences/me)" },
  { href: "/workshop-access", label: "Workshop access", icon: KeyRound, group: "Account", can: everyone, gate: "get_current_user (POST /workshops/access-requests)" },
  { href: "/feedback", label: "Give app feedback", icon: MessageSquare, group: "Account", can: everyone, gate: "get_current_user (PUT /feedback/me)" }
];

export function isNavItemVisible(item: NavItem, user: User | null | undefined, adminMode: boolean): boolean {
  if (!user || !item.can(user)) return false;
  if (item.adminSurface && isAdmin(user)) return adminMode;
  return true;
}

const spring = { type: "spring" as const, stiffness: 260, damping: 30 };

const NO_MOTION = { duration: 0 } as const;

export function DynamicIslandNav() {
  const { user, logout } = useAuth();
  const adminView = useAdminView();
  const { adminMode, canAdmin, toggleAdminView } = adminView;
  const adminAvailable = (adminView as { available?: boolean }).available ?? canAdmin;
  const router = useRouter();
  const pathname = usePathname();
  const [active, setActive] = useState<string | null>(null);
  const [sheetOpen, setSheetOpen] = useState(false);
  const [searchOpen, setSearchOpen] = useState(false);
  const [compact, setCompact] = useState(false);
  const { scrollY } = useScroll();
  const sheetRef = useRef<HTMLDivElement | null>(null);
  const menuButtonRef = useRef<HTMLButtonElement | null>(null);
  const sheetOpenerRef = useRef<HTMLButtonElement | null>(null);
  const reduce = useAppReducedMotion();
  const islandTransition = reduce ? NO_MOTION : spring;

  useMotionValueEvent(scrollY, "change", (latest) => {
    const previous = scrollY.getPrevious() ?? 0;
    if (latest < 24) setCompact(false);
    else if (latest > previous + 2) setCompact(true);
    else if (latest < previous - 2) setCompact(false);
  });

  useEffect(() => {
    setSheetOpen(false);
    setSearchOpen(false);
    setActive(null);
  }, [pathname]);

  const closeSheet = useCallback(() => {
    setSheetOpen(false);
    (sheetOpenerRef.current ?? menuButtonRef.current)?.focus();
  }, []);

  const toggleSheetFrom = useCallback((button: HTMLButtonElement) => {
    sheetOpenerRef.current = button;
    setSheetOpen((value) => !value);
  }, []);

  useEffect(() => {
    if (!sheetOpen) return;
    lockPageScroll();
    return unlockPageScroll;
  }, [sheetOpen]);

  useEffect(() => {
    if (!sheetOpen) return;
    const panel = sheetRef.current;
    if (!panel) return;

    const focusable = () =>
      Array.from(
        panel.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])')
      );

    focusable()[0]?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        closeSheet();
        return;
      }
      if (event.key !== "Tab") return;
      const stops = focusable();
      if (stops.length === 0) return;
      const first = stops[0];
      const last = stops[stops.length - 1];
      const focused = document.activeElement as HTMLElement | null;
      const outside = !focused || !panel.contains(focused);
      if (event.shiftKey && (outside || focused === first)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (outside || focused === last)) {
        event.preventDefault();
        first.focus();
      }
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [closeSheet, sheetOpen]);

  const visibleItems = useMemo(
    () => NAV_ITEMS.filter((item) => isNavItemVisible(item, user, adminMode)),
    [user, adminMode]
  );
  const rootItems = visibleItems.filter((item) => item.group === null);
  const visibleGroups = NAV_GROUPS.map((label) => ({
    label,
    items: visibleItems.filter((item) => item.group === label)
  })).filter((group) => group.items.length > 0);

  const hubEntryVisible = visibleItems.some((item) => item.href === PENDING_ACCESS_BADGE_HREF);
  const pendingAccess = usePendingAccessCount(hubEntryVisible && canManageAccessRoster(user));
  const pendingAccessCount = pendingAccess?.pending ?? 0;

  const tasksEntryVisible = visibleItems.some((item) => item.href === OPEN_TASK_BADGE_HREF);
  const openTaskCount = useOpenTaskCount(tasksEntryVisible) ?? 0;

  const sanctionEntryVisible = visibleItems.some((item) => item.href === AWAITING_SANCTION_BADGE_HREF);
  const awaitingSanctionCount = useAwaitingSanctionCount(sanctionEntryVisible) ?? 0;
  const approvalEntryVisible = visibleItems.some((item) => item.href === AWAITING_APPROVAL_BADGE_HREF);
  const awaitingApprovalCount = useAwaitingApprovalCount(approvalEntryVisible) ?? 0;
  const inspectionEntryVisible = visibleItems.some((item) => item.href === AWAITING_INSPECTION_BADGE_HREF);
  const awaitingInspectionCount = useAwaitingInspectionCount(inspectionEntryVisible) ?? 0;
  const unreadNotifications = useUnreadNotificationCount(Boolean(user));

  if (!user) return null;

  async function handleLogout() {
    await logout();
    router.replace("/login");
  }

  let activeBase: string | null = null;
  for (const item of visibleItems) {
    const base = item.href.split("?")[0];
    if (pathname !== base && !pathname.startsWith(`${base}/`)) continue;
    if (!activeBase || base.length > activeBase.length) activeBase = base;
  }

  const isActivePath = (href: string) => href.split("?")[0] === activeBase;

  const islandEntry = (item: NavItem) => ({
    label: item.label,
    href: item.href,
    icon: item.icon,
    badge: item.href === NOTIFICATIONS_HREF ? unreadNotifications : 0
  });
  const islandRoots = rootItems.map(islandEntry);
  const islandGroups = visibleGroups.map((group) => ({ label: group.label, entries: group.items.map(islandEntry) }));
  const currentLabel = visibleItems.find((item) => isActivePath(item.href))?.label ?? null;

  return (
    <>
      <div className="nav-island-frame pointer-events-none fixed inset-x-0 top-3 z-50 flex justify-center max-lg:hidden">
        <motion.header
          layout
          transition={islandTransition}
          onMouseLeave={() => setActive(null)}
          className={cn(
            "pointer-events-auto flex items-center gap-1 rounded-full border border-line-200 bg-card/85 shadow-island backdrop-blur-xl",
            compact ? "px-3 py-1.5" : "px-4 py-2"
          )}
        >
          <motion.div
            key={compact ? "brand-compact" : "brand-full"}
            initial={reduce ? { x: 0, opacity: 1 } : { x: -32, opacity: 0 }}
            animate={{ x: 0, opacity: 1 }}
            transition={islandTransition}
          >
            <Link href="/dashboard" className="flex min-w-fit items-center gap-2 pr-2 text-ink-900">
              <WorkshopLogo className={cn("rounded-lg", compact ? "h-7 w-7" : "h-8 w-8")} />
              <span className={cn("whitespace-nowrap font-display font-bold tracking-tight", compact ? "text-sm" : "text-base")}>
                Design Prototype Workshop
              </span>
            </Link>
          </motion.div>

          <AnimatePresence initial={false}>
            {!compact ? (
              <motion.nav
                initial={{ opacity: 0, width: 0 }}
                animate={{ opacity: 1, width: "auto" }}
                exit={{ opacity: 0, width: 0 }}
                transition={reduce ? NO_MOTION : { duration: 0.22, ease: "easeOut" }}
                className="hidden items-center gap-5 overflow-visible whitespace-nowrap pl-2 pr-1 lg:flex"
                aria-label="Primary"
              >
                {rootItems.map((item) => (
                  <Link
                    key={item.href}
                    href={item.href}
                    aria-current={isActivePath(item.href) ? "page" : undefined}
                    className={cn(
                      "text-sm font-medium transition",
                      isActivePath(item.href) ? "text-purple-700" : "text-ink-700 hover:text-ink-900"
                    )}
                  >
                    {item.label}
                  </Link>
                ))}
                {visibleGroups.map((group) => (
                  <MenuItem key={group.label} setActive={setActive} active={active} item={group.label}>
                    <div className="flex flex-col space-y-3 text-sm">
                      {group.items.map((item) => (
                        <HoveredLink key={item.href} href={item.href}>
                          <span className="inline-flex items-center gap-2">
                            <item.icon className="h-3.5 w-3.5 text-ink-300" aria-hidden />
                            {item.label}
                            {item.href === PENDING_ACCESS_BADGE_HREF ? (
                              <PendingAccessBadge count={pendingAccessCount} />
                            ) : null}
                            {item.href === OPEN_TASK_BADGE_HREF ? <OpenTaskBadge count={openTaskCount} /> : null}
                            {item.href === AWAITING_SANCTION_BADGE_HREF ? (
                              <AwaitingSanctionBadge count={awaitingSanctionCount} />
                            ) : null}
                            {item.href === AWAITING_APPROVAL_BADGE_HREF ? (
                              <AwaitingApprovalBadge count={awaitingApprovalCount} />
                            ) : null}
                            {item.href === AWAITING_INSPECTION_BADGE_HREF ? (
                              <AwaitingInspectionBadge count={awaitingInspectionCount} />
                            ) : null}
                          </span>
                        </HoveredLink>
                      ))}
                    </div>
                  </MenuItem>
                ))}
              </motion.nav>
            ) : null}
          </AnimatePresence>

          <div className="ml-1 flex items-center gap-1.5">
            {adminAvailable && !compact ? (
              <span
                data-testid="island-admin-view"
                className={cn(
                  "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1.5 text-xs font-medium transition",
                  adminMode ? "border-purple-300 bg-purple-50 text-purple-700" : "border-line-200 bg-card text-ink-500"
                )}
              >
                {adminMode ? <Eye className="h-3.5 w-3.5" aria-hidden /> : <EyeOff className="h-3.5 w-3.5" aria-hidden />}
                <ToggleSwitch
                  label="Admin view"
                  showLabel
                  checked={adminMode}
                  onChange={() => toggleAdminView()}
                  className="min-h-0! gap-1.5 px-0 [&>span:last-child]:-mx-1.5 [&>span:last-child]:-my-[3.5px] [&>span:last-child]:scale-75"
                />
              </span>
            ) : null}
            <NotificationBell active={isActivePath("/notifications")} />
            <button
              ref={menuButtonRef}
              type="button"
              onClick={(event) => toggleSheetFrom(event.currentTarget)}
              aria-label="Toggle navigation menu"
              aria-expanded={sheetOpen}
              className="inline-flex h-9 w-9 items-center justify-center rounded-full border border-line-200 bg-card text-ink-900 transition hover:bg-surface-50 pointer-coarse:h-11 pointer-coarse:w-11"
            >
              {sheetOpen ? <X className="h-4 w-4" aria-hidden /> : <MenuIcon className="h-4 w-4" aria-hidden />}
            </button>
          </div>
        </motion.header>
      </div>

      <AnimatePresence>
        {sheetOpen ? (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={reduce ? NO_MOTION : { duration: 0.18 }}
            className="nav-sheet-overlay fixed inset-0 z-90"
          >
            <div
              aria-hidden
              onClick={closeSheet}
              style={{ touchAction: "none" }}
              className="absolute inset-0 bg-ink-900/20 backdrop-blur-xs"
            />
            <motion.div
              ref={sheetRef}
              role="dialog"
              aria-modal="true"
              aria-label="Navigation"
              initial={reduce ? { y: 0, opacity: 0 } : { y: -16, opacity: 0 }}
              animate={{ y: 0, opacity: 1 }}
              exit={reduce ? { y: 0, opacity: 0 } : { y: -16, opacity: 0 }}
              transition={islandTransition}
              className="nav-sheet relative mx-auto w-[min(680px,92vw)] rounded-xl border border-line-200 bg-card shadow-lg"
            >
              <div className="grid gap-1 sm:grid-cols-2">
                {visibleItems.map((item) => (
                  <Link
                    key={item.href}
                    href={item.href}
                    onClick={() => setSheetOpen(false)}
                    aria-current={isActivePath(item.href) ? "page" : undefined}
                    className={cn(
                      "inline-flex items-center gap-2.5 rounded-md px-3 py-2.5 text-sm font-medium transition",
                      isActivePath(item.href) ? "bg-purple-50 text-purple-700" : "text-ink-700 hover:bg-surface-50 hover:text-ink-900"
                    )}
                  >
                    <item.icon className="h-4 w-4 shrink-0 text-ink-300" aria-hidden />
                    {item.label}
                    {item.href === PENDING_ACCESS_BADGE_HREF ? <PendingAccessBadge count={pendingAccessCount} /> : null}
                    {item.href === OPEN_TASK_BADGE_HREF ? <OpenTaskBadge count={openTaskCount} /> : null}
                    {item.href === AWAITING_SANCTION_BADGE_HREF ? (
                      <AwaitingSanctionBadge count={awaitingSanctionCount} />
                    ) : null}
                    {item.href === AWAITING_APPROVAL_BADGE_HREF ? (
                      <AwaitingApprovalBadge count={awaitingApprovalCount} />
                    ) : null}
                    {item.href === AWAITING_INSPECTION_BADGE_HREF ? (
                      <AwaitingInspectionBadge count={awaitingInspectionCount} />
                    ) : null}
                  </Link>
                ))}
              </div>
              <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-line-200 pt-4">
                {adminAvailable ? (
                  <ToggleSwitch label="Admin view" showLabel checked={adminMode} onChange={() => toggleAdminView()} />
                ) : null}
                <div className="ml-auto text-right text-xs">
                  <div className="font-medium text-ink-900">{user.name}</div>
                  <div className="text-ink-500">{roleLabel(user.role)}</div>
                </div>
                <button onClick={handleLogout} data-danger className="field-danger">
                  <LogOut className="h-4 w-4" aria-hidden />
                  Logout
                </button>
              </div>
            </motion.div>
          </motion.div>
        ) : null}
      </AnimatePresence>

      <AppSearchHeader
        appName={APP_NAME}
        logo={(className) => <WorkshopLogo className={className} />}
        onOpenSearch={() => setSearchOpen(true)}
        searchOpen={searchOpen}
      />
      <AppSearchPanel
        open={searchOpen}
        appName={APP_NAME}
        screens={visibleItems.map((item) => ({ label: item.label, href: item.href, icon: item.icon }))}
        onClose={() => setSearchOpen(false)}
      />
      <BottomIslandNav
        roots={islandRoots}
        groups={islandGroups}
        currentLabel={currentLabel}
        onOpenMenu={toggleSheetFrom}
        menuOpen={sheetOpen}
        hidden={searchOpen}
      />
    </>
  );
}

const APP_NAME = "Design Workshop";
