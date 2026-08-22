"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion, useMotionValueEvent, useScroll } from "framer-motion";
import {
  Activity,
  BadgeCheck,
  Boxes,
  Brush,
  ChartNoAxesCombined,
  ClipboardCheck,
  ClipboardList,
  Compass,
  DraftingCompass,
  Eye,
  EyeOff,
  FileSpreadsheet,
  FolderTree,
  Gauge,
  GitBranch,
  IdCard,
  Image as ImageIcon,
  KeyRound,
  Layers,
  LogOut,
  MapPinned,
  Menu as MenuIcon,
  MessageSquare,
  Search,
  Settings as SettingsIcon,
  Share2,
  SlidersHorizontal,
  UserCog,
  Users,
  Wrench,
  X,
  type LucideIcon
} from "lucide-react";

import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { usePendingAccessCount } from "@/components/hooks/usePendingAccessCount";
import { HoveredLink, MenuItem } from "@/components/ui/navbar-menu";
import { WorkshopLogo } from "@/components/WorkshopLogo";
import {
  canCreateRecords,
  canDownloadDataset,
  canManageAccessRoster,
  canManageCrafts,
  canManageDesignerRoster,
  canManageUsers,
  canManageWorkshops,
  canReview,
  canRunDesignWorkshops,
  isAdmin,
  roleLabel
} from "@/lib/permissions";
import { lockPageScroll, unlockPageScroll } from "@/lib/scrollLock";
import { cn } from "@/lib/utils";
import type { User } from "@/lib/types";

/**
 * Dropdown groups in the desktop bar, in render order. `null` = a standalone link in the bar
 * (Dashboard and the Walkthrough, the two places a newcomer starts). "Account" sits last because it
 * holds what belongs to the person rather than to the repository — their own settings and feedback.
 */
const NAV_GROUPS = ["Record", "Browse", "Admin", "Account"] as const;
type NavGroup = (typeof NAV_GROUPS)[number];

/**
 * The entry that wears the "people are waiting to be let in" count.
 *
 * The hub rather than the allow-list itself, because the allow-list has no nav entry of its own —
 * the same rule the designer roster follows and for the same reason (see the NAV_ITEMS note). A
 * constant rather than a literal in two places, so the badge and the fetch that feeds it can only
 * ever be about one destination.
 */
const PENDING_ACCESS_BADGE_HREF = "/admin";

/**
 * The count, drawn so it survives being glanced at.
 *
 * NOT A BARE DIGIT. A number alone in a corner is decoration; this says what it counts, in text, so
 * an admin who has never seen it before does not have to open the screen to find out. `title`
 * carries the sentence for a pointer and the sr-only span carries it for a screen reader, which
 * would otherwise announce "Settings hub 3" and leave the listener to guess at the three.
 */
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

type NavItem = {
  href: string;
  label: string;
  icon: LucideIcon;
  group: NavGroup | null;
  /**
   * The entitlement this destination needs. When it returns false the entry is NOT RENDERED —
   * never rendered disabled — so the menu only ever offers what the API would actually allow.
   */
  can: (user: User) => boolean;
  /** The backend dependency `can` mirrors (app/core/deps.py); keep the two in step. */
  gate: string;
  /** Admin-tier chrome: admins additionally need admin view ON. Never widens `can`. */
  adminSurface?: boolean;
};

const everyone = () => true;

/**
 * The single source of truth for navigation. Every destination carries its own predicate, so the
 * gate lives next to the item instead of being scattered across the JSX, and one list drives both
 * the desktop dropdowns and the full sheet. Labels are the EXACT Android drawer/action names
 * (MainActivity EntryMode.actionTitle) so both clients speak one language.
 */
export const NAV_ITEMS: NavItem[] = [
  { href: "/dashboard", label: "Dashboard", icon: Gauge, group: null, can: everyone, gate: "get_current_user" },
  // Onboarding, deliberately ungated: the Walkthrough teaches the documentation process itself, so
  // it has to reach the people who have not earned any capability yet — a crowdsource volunteer on
  // their first day needs it MORE than an admin does. It is a static page that calls no API.
  { href: "/guide", label: "Walkthrough", icon: Compass, group: null, can: everyone, gate: "none (static page)" },

  // Record — every entry here creates something, so it follows the CREATE dependency, not the list
  // one. Hiding an entry therefore never hides the DATA behind it: the list endpoints stay open to
  // any signed-in user, and "Browse records" / "View Data" remain the read route to the same records.
  { href: "/artisans", label: "Record artisan", icon: Users, group: "Record", can: canCreateRecords, gate: "require_record_creator" },
  { href: "/products", label: "Record product", icon: Boxes, group: "Record", can: canCreateRecords, gate: "require_record_creator" },
  { href: "/processes", label: "Document process", icon: GitBranch, group: "Record", can: canCreateRecords, gate: "require_record_creator" },
  { href: "/tools", label: "Record tool", icon: Wrench, group: "Record", can: canCreateRecords, gate: "require_record_creator" },
  // Answering an interview is open to everyone — volunteers contribute answers and media.
  { href: "/questionnaire", label: "Take interview", icon: ClipboardList, group: "Record", can: everyone, gate: "get_current_user" },
  { href: "/media", label: "Upload media", icon: ImageIcon, group: "Record", can: everyone, gate: "get_current_user" },
  { href: "/crafts", label: "Add craft", icon: Brush, group: "Record", can: canManageCrafts, gate: "require_craft_manager" },
  { href: "/workshops", label: "Record workshop", icon: Users, group: "Record", can: canManageWorkshops, gate: "require_workshop_manager" },
  // The 22-stage Design & Prototype Workshop record.
  //
  // `can_run_design_workshops`, and this line used to say `canCreateRecords`. `POST
  // /design-workshops` runs BOTH `assert_can_create_records` AND `_require_designer`
  // (backend/app/api/routes/design_workshops.py:391-392) and the second one binds, so a RESEARCHER
  // — and a PROFESSOR, who outranks a designer and is still outside the set — saw this entry,
  // pressed it, and landed on the route guard's "Designer access required" panel, which
  // `lib/permissions.ts` has been enforcing on the same path all along. A nav entry that only ever
  // opens a padlock is worse than no entry.
  //
  // THE READS ARE OPEN AND THIS ENTRY DOES NOT PRETEND OTHERWISE — see the `gate` below, which is
  // the honest one. `_require_designer` sits on the writes only (create :392, patch :439, stage
  // :539); `list_design_workshops` (:304) and `GET /{workshop_id}` (:419) take `get_current_user`
  // and filter rows through `visible_to_clause`. Hiding the row is therefore the same kind of
  // narrowing as `/designers/profile` further down, not a mirror of a refusal — and the difference
  // matters the next time somebody is tempted to move a WRITE control behind a hidden link.
  // Contrast `/questionnaires` immediately below, where every route really does begin with
  // `_require_designer`, reads included.
  //
  // LABELLED IN THE PLURAL while the dashboard tile beside it is singular ("Design workshop"), and
  // the difference is deliberate on both clients: the tile names the THING you are about to make,
  // the menu names the LIST you are about to open. Android's dashboard card copies the tile
  // (`DesignWorkshopCard.LABEL`) and its own menu row copies this one; a Kotlin test asserts the
  // two differ so neither can be "tidied" into the other.
  {
    href: "/design-workshops",
    label: "Design workshops",
    icon: DraftingCompass,
    group: "Record",
    can: canRunDesignWorkshops,
    gate: "get_current_user on the list; _require_designer on every write (narrowed here to can_run_design_workshops)"
  },
  // A questionnaire the designer authored themselves, from the .xlsx pro-forma. DISTINCT FROM "Take
  // interview" above, which is the one shared artisan questionnaire every researcher answers — two
  // different instruments whose routes differ by a single character, so the labels are worded to be
  // told apart at a glance rather than by reading the URL. Gated on `can_run_design_workshops`
  // because every route under /api/questionnaires begins with `_require_designer`; an ungated entry
  // would land a researcher on a refusal.
  {
    href: "/questionnaires",
    label: "My questionnaires",
    icon: FileSpreadsheet,
    group: "Record",
    can: canRunDesignWorkshops,
    gate: "can_run_design_workshops (_require_designer)"
  },

  // Browse
  { href: "/activity", label: "My Activity", icon: Activity, group: "Browse", can: everyone, gate: "get_current_user" },
  // Everyone can be a task assignee; the "assign" half of the page is gated inside it.
  { href: "/tasks", label: "Tasks", icon: ClipboardCheck, group: "Browse", can: everyone, gate: "get_current_user" },
  { href: "/search", label: "Browse records", icon: Search, group: "Browse", can: everyone, gate: "get_current_user" },
  // The third way of reading the whole corpus, and it sits next to the other two on purpose:
  // /search reads it as a list, /data as a folder tree, /map as a place. Ungated to match /search,
  // because GET /map/points takes any signed-in caller and has already filtered every pin through
  // `visibility_where` — so a volunteer and a professor open the same page and see different
  // numbers on it. Built and reachable only by typing the URL until this line existed.
  { href: "/map", label: "Map", icon: MapPinned, group: "Browse", can: everyone, gate: "get_current_user" },
  { href: "/data", label: "View Data", icon: FolderTree, group: "Browse", can: canDownloadDataset, gate: "require_dataset_downloader" },
  // Browse rather than Record: an interview is STORED once per exact set of artisans, so one
  // artisan's answers are scattered over several entries and this is the only surface that reads
  // them back as one document. "Take interview" above writes; this one only reads, and
  // GET /questionnaire/artisans/{id}/consolidated asks for nothing but a login.
  {
    href: "/questionnaire/consolidated",
    label: "Consolidated questionnaire",
    icon: Layers,
    group: "Browse",
    can: everyone,
    gate: "get_current_user"
  },
  { href: "/sharing", label: "Share data access", icon: Share2, group: "Browse", can: everyone, gate: "get_current_user" },
  // Linking a tool to an artisan needs a tool or an artisan of your own — both need record creation.
  // The endpoint itself only requires a login and then checks ownership per artisan, so this is the
  // closest STATIC mirror of a dynamic rule: nobody below Field Contributor owns either side.
  { href: "/tools?assign=1", label: "Assign tools to artisans", icon: Wrench, group: "Browse", can: canCreateRecords, gate: "get_current_user + owner/EDIT-grant/admin per artisan" },

  // Admin — capability holders below admin (professors, grantees) keep these permanently; admins,
  // who own the toggle, see them only while admin view is ON.
  // NOT adminSurface. Reviewing is a Field Contributor+ capability, not admin chrome, and AppShell's
  // ADMIN_CHROME_ROUTES deliberately leaves /review open while admin view is off. Flagging it here
  // too removed the link from an admin who still had the route — an open page with no way to reach it.
  { href: "/review", label: "Review", icon: Eye, group: "Browse", can: canReview, gate: "require_reviewer" },
  { href: "/admin", label: "Settings hub", icon: SlidersHorizontal, group: "Admin", can: isAdmin, gate: "require_admin", adminSurface: true },
  // Cross-workshop analytics. `isAdmin` mirrors `require_admin` on GET /api/analytics/design-workshops
  // — a DESIGNER is outside it, deliberately: this reads every cluster in the archive, which is
  // wider than any per-workshop grant. Unlike the designer roster below, this IS a destination
  // rather than configuration — an admin comes here to read something — so it earns a nav entry as
  // well as its tile on the hub.
  { href: "/admin/analytics", label: "Cross-workshop analytics", icon: ChartNoAxesCombined, group: "Admin", can: isAdmin, gate: "require_admin", adminSurface: true },
  { href: "/users", label: "Manage users", icon: UserCog, group: "Admin", can: canManageUsers, gate: "require_professor", adminSurface: true },
  // NO DESIGNER ROSTER ENTRY HERE. It is reached from the settings hub — "Settings hub" above —
  // and from nowhere else. The roster is administrative configuration rather than a place anybody
  // navigates to in the course of a day's work, and it used to sit here, on the dashboard AND in
  // the hub: three entrances to one screen, each with its own copy of the gate to keep in step.
  // The route itself is still `can_manage_designer_roster` and `GET /designers/roster` still
  // refuses everyone below admin, so removing the link removes a link and not a protection.

  // Account — personal, so nothing here is role-gated.
  // /settings is TWO pages in one: the left column is the repository's global configuration
  // (require_master_admin, and it renders its own lock panel for everyone else) while the right
  // column is this account's theme, reduced motion, larger text and high contrast, saved through
  // PUT /preferences/me, which asks for nothing but a login. Gating the entry on isMasterAdmin left
  // every other user with NO route to their own accessibility switches, so it is `everyone` — the
  // master admin's global column is also linked from the /admin hub tile "App settings".
  // ACCOUNT and not Record, and the placement is the honest one: this edits the PERSON, not the
  // repository. A designer looking for "where do I change the name on my reports" looks under their
  // own account, not under the list of things they can create — Android puts it in the same group
  // and calls it the same thing.
  //
  // Gated on the DESIGNER rank although `PUT /designers/me/profile` takes any signed-in caller: a
  // `DesignerProfile` is only ever printed on a design-workshop report, and only Designer and above
  // can generate one, so offering the screen to a crowdsource volunteer would be offering them a
  // form whose twenty answers nothing in the app would read back. Deliberately NOT mirrored by a
  // ROUTE_GUARDS row — the endpoint really is open, and a guard claiming otherwise would lock a
  // professor out of a page the server would serve them.
  {
    href: "/designers/profile",
    label: "My designer profile",
    icon: IdCard,
    group: "Account",
    can: canRunDesignWorkshops,
    gate: "get_current_user (narrowed here to can_run_design_workshops)"
  },
  { href: "/settings", label: "Settings", icon: SettingsIcon, group: "Account", can: everyone, gate: "get_current_user (PUT /preferences/me)" },
  // Android's Workshop access entry, which the menu had no equivalent for: the destination is the
  // fork, and for anyone below admin it opens their own request page directly. Ungated because
  // asking is not an admin act (POST /workshops/access-requests takes any signed-in caller) — the
  // console behind the admin half of the fork carries `require_admin` on its own route instead.
  { href: "/workshop-access", label: "Workshop access", icon: KeyRound, group: "Account", can: everyone, gate: "get_current_user (POST /workshops/access-requests)" },
  { href: "/feedback", label: "Give app feedback", icon: MessageSquare, group: "Account", can: everyone, gate: "get_current_user (PUT /feedback/me)" }
];

/**
 * Entitlement first, admin view second: `can` is consulted before the toggle is even looked at, so
 * switching admin view ON can never surface a destination the API would 403 — it only hides admin
 * chrome from an admin browsing as an ordinary user.
 */
export function isNavItemVisible(item: NavItem, user: User | null | undefined, adminMode: boolean): boolean {
  if (!user || !item.can(user)) return false;
  if (item.adminSurface && isAdmin(user)) return adminMode;
  return true;
}

const spring = { type: "spring" as const, stiffness: 260, damping: 30 };

export function DynamicIslandNav() {
  const { user, logout } = useAuth();
  const { adminMode, canAdmin, toggleAdminView } = useAdminView();
  const router = useRouter();
  const pathname = usePathname();
  const [active, setActive] = useState<string | null>(null);
  const [sheetOpen, setSheetOpen] = useState(false);
  const [compact, setCompact] = useState(false);
  const { scrollY } = useScroll();
  const sheetRef = useRef<HTMLDivElement | null>(null);
  const menuButtonRef = useRef<HTMLButtonElement | null>(null);

  // Compact while travelling downward, expand at the top or on any upward scroll —
  // so the full menu is always one flick away without returning to the top.
  useMotionValueEvent(scrollY, "change", (latest) => {
    const previous = scrollY.getPrevious() ?? 0;
    if (latest < 24) setCompact(false);
    else if (latest > previous + 2) setCompact(true);
    else if (latest < previous - 2) setCompact(false);
  });

  useEffect(() => {
    setSheetOpen(false);
    setActive(null);
  }, [pathname]);

  // Dismissal always hands focus back to the hamburger, whichever way the sheet was shut. Losing it
  // to <body> would strand a keyboard user at the top of the document, several tab stops from where
  // they were.
  const closeSheet = useCallback(() => {
    setSheetOpen(false);
    menuButtonRef.current?.focus();
  }, []);

  /**
   * The open sheet freezes the page behind it.
   *
   * Through `lib/scrollLock.ts` rather than by writing the class on <html> here, which is what this
   * effect used to do. The protocol is unchanged — the lock lives on the root element because iOS
   * Safari ignores `overflow: hidden` on the body, the scroll POSITION survives so a reader who
   * opened the menu halfway down a long record comes back to the same paragraph, and the width of
   * the scrollbar the lock removes is handed back through `--nav-scroll-gutter` so nothing centred
   * moves sideways. What changes is that the class is now REFCOUNTED across all three surfaces that
   * freeze the page: a dialog mounted in the protected layout closing while this sheet is open no
   * longer takes the lock away with it.
   */
  useEffect(() => {
    if (!sheetOpen) return;
    lockPageScroll();
    return unlockPageScroll;
  }, [sheetOpen]);

  // Keyboard path: opening the sheet moves focus into it, Tab cycles WITHIN it, and Escape closes it
  // and hands focus back to the button that opened it — the desktop dropdowns are pointer-only, so
  // this sheet is the keyboard route to every destination.
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
      // The sheet is aria-modal: tabbing out of it would walk a screen reader through a page it has
      // just been told is inert, so both ends of the list wrap round instead.
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

  // One filtered list feeds both renderers, so a hidden entry cannot reappear in the other menu.
  const visibleItems = useMemo(
    () => NAV_ITEMS.filter((item) => isNavItemVisible(item, user, adminMode)),
    [user, adminMode]
  );
  const rootItems = visibleItems.filter((item) => item.group === null);
  const visibleGroups = NAV_GROUPS.map((label) => ({
    label,
    items: visibleItems.filter((item) => item.group === label)
  })).filter((group) => group.items.length > 0);

  /**
   * HOW MANY PEOPLE ARE WAITING TO BE LET INTO THE APPLICATION — the badge on "Settings hub".
   *
   * ── WHY THE COUNT IS HERE AT ALL ────────────────────────────────────────────────────────────
   * The requirement asks that admins be NOTIFIED when somebody is turned away, and this codebase
   * has no email sender, no push transport and no job runner to build one from. What it has is
   * admins who open screens, so the notification is a number on the chrome that is already on every
   * page. The queue itself is one tap away, on the hub tile this badge points at.
   *
   * ── WHY IT BADGES "Settings hub" AND NOT AN ENTRY OF ITS OWN ────────────────────────────────
   * See the note further up this file: the designer roster deliberately has no nav entry, because
   * administrative configuration reached from three places is three copies of one gate to keep in
   * step. The allow-list follows that rule, so the number rides on the parent an admin already uses
   * to reach it. Android reaches its own roster from the menu directly and badges THAT entry — the
   * NUMBER is the thing that must match across the two clients, not the route to it.
   *
   * ── WHY THE FETCH IS TIED TO THE ENTRY BEING VISIBLE ────────────────────────────────────────
   * `enabled` is "the badged entry is actually on screen", which folds in the permission AND the
   * admin-view toggle in one expression that cannot drift from what is rendered. An admin browsing
   * as an ordinary user is not shown admin chrome and does not spend a request on it; an account
   * that could not read the endpoint never asks and is never 403'd. Shared with the hub tile through
   * one module-level store, so the badge and the tile can never disagree — see the hook.
   */
  const hubEntryVisible = visibleItems.some((item) => item.href === PENDING_ACCESS_BADGE_HREF);
  const pendingAccess = usePendingAccessCount(hubEntryVisible && canManageAccessRoster(user));
  const pendingAccessCount = pendingAccess?.pending ?? 0;

  if (!user) return null;

  async function handleLogout() {
    await logout();
    router.replace("/login");
  }

  /**
   * The one entry that is "current", resolved most-specific-first.
   *
   * A prefix test on its own marks two entries at once as soon as a destination NESTS under another
   * — /questionnaire/consolidated is inside /questionnaire — and `aria-current="page"` on two links
   * tells a screen reader the reader is in two places. Longest matching base wins, the same way
   * ROUTE_GUARDS resolves an overlap. Two entries sharing one base (/tools and /tools?assign=1) both
   * still light up, which is right: they are the same page.
   */
  let activeBase: string | null = null;
  for (const item of visibleItems) {
    const base = item.href.split("?")[0];
    if (pathname !== base && !pathname.startsWith(`${base}/`)) continue;
    if (!activeBase || base.length > activeBase.length) activeBase = base;
  }

  const isActivePath = (href: string) => href.split("?")[0] === activeBase;

  return (
    <>
      <div className="nav-island-frame pointer-events-none fixed inset-x-0 top-3 z-50 flex justify-center">
        <motion.header
          layout
          transition={spring}
          onMouseLeave={() => setActive(null)}
          className={cn(
            "pointer-events-auto flex items-center gap-1 rounded-full border border-line-200 bg-card/85 shadow-island backdrop-blur-xl",
            compact ? "px-3 py-1.5" : "px-4 py-2"
          )}
        >
          {/* Brand: swoops in from the left every time the island compacts on a downward scroll. */}
          <motion.div
            key={compact ? "brand-compact" : "brand-full"}
            initial={{ x: -32, opacity: 0 }}
            animate={{ x: 0, opacity: 1 }}
            transition={spring}
          >
            <Link href="/dashboard" className="flex min-w-fit items-center gap-2 pr-2 text-ink-900">
              <WorkshopLogo className={cn("rounded-lg", compact ? "h-7 w-7" : "h-8 w-8")} />
              <span className={cn("whitespace-nowrap font-display font-bold tracking-tight", compact ? "text-sm" : "text-base")}>
                Design Prototype Workshop
              </span>
            </Link>
          </motion.div>

          {/* Desktop navigation, hidden while compact. */}
          <AnimatePresence initial={false}>
            {!compact ? (
              <motion.nav
                initial={{ opacity: 0, width: 0 }}
                animate={{ opacity: 1, width: "auto" }}
                exit={{ opacity: 0, width: 0 }}
                transition={{ duration: 0.22, ease: "easeOut" }}
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
            {/* Offered to admins only — and it can merely hide admin chrome, never unlock it. */}
            {canAdmin && !compact ? (
              <button
                type="button"
                onClick={toggleAdminView}
                aria-pressed={adminMode}
                title={adminMode ? "Admin view: ON" : "Admin view: OFF"}
                className={cn(
                  "hidden items-center gap-1.5 rounded-full border px-2.5 py-1.5 text-xs font-medium transition sm:inline-flex",
                  adminMode
                    ? "border-purple-300 bg-purple-50 text-purple-700"
                    : "border-line-200 bg-card text-ink-500 hover:bg-surface-50"
                )}
              >
                {adminMode ? <Eye className="h-3.5 w-3.5" aria-hidden /> : <EyeOff className="h-3.5 w-3.5" aria-hidden />}
                {adminMode ? "Admin view: ON" : "Admin view: OFF"}
              </button>
            ) : null}
            <button
              ref={menuButtonRef}
              type="button"
              onClick={() => setSheetOpen((value) => !value)}
              aria-label="Toggle navigation menu"
              aria-expanded={sheetOpen}
              className="inline-flex h-9 w-9 items-center justify-center rounded-full border border-line-200 bg-card text-ink-900 transition hover:bg-surface-50"
            >
              {sheetOpen ? <X className="h-4 w-4" aria-hidden /> : <MenuIcon className="h-4 w-4" aria-hidden />}
            </button>
          </div>
        </motion.header>
      </div>

      {/* Full navigation sheet: keyboard-reachable path to every destination the user qualifies for.

          The overlay's rung is `z-[90]`, not the `z-40` it shipped with, and the reason is
          `AppShell`'s <main>. That element used to carry `z-10`, which made it a stacking context
          and quietly capped everything a page mounts — however high the page declared it — below
          this scrim. It carries no z-index now (the media lightbox and the full-screen editor, both
          `z-[100]`, could not otherwise clear the island), so in-page fixed chrome competes with
          this overlay directly in the ROOT stacking context, and a tie there is settled by tree
          order alone — which this loses, because the nav renders BEFORE <main>. `UploadTray` is
          fixed at `z-40`, so at the old rung an upload in flight painted its dock over an open
          `aria-modal` sheet: undimmed and still clickable. 90 clears anything a page mounts and
          stays below the `z-[100]` dialog rung, so this scrim still yields to a true modal. */}
      <AnimatePresence>
        {sheetOpen ? (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.18 }}
            className="nav-sheet-overlay fixed inset-0 z-[90]"
          >
            {/* The scrim is its own element rather than the sheet's parent: `touch-action: none` is
                what stops a drag on the dimmed page from panning it on iOS, and from an ancestor
                that same declaration would also cancel the scroll gesture inside the sheet. */}
            <div
              aria-hidden
              onClick={closeSheet}
              style={{ touchAction: "none" }}
              className="absolute inset-0 bg-ink-900/20 backdrop-blur-sm"
            />
            <motion.div
              ref={sheetRef}
              role="dialog"
              aria-modal="true"
              aria-label="Navigation"
              initial={{ y: -16, opacity: 0 }}
              animate={{ y: 0, opacity: 1 }}
              exit={{ y: -16, opacity: 0 }}
              transition={spring}
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
                    {/* The sheet is the keyboard and touch route to everything, so the badge has to
                        be here as well as in the desktop dropdown — a notification that only exists
                        on a pointer-driven hover menu does not reach an admin on a tablet. */}
                    {item.href === PENDING_ACCESS_BADGE_HREF ? <PendingAccessBadge count={pendingAccessCount} /> : null}
                  </Link>
                ))}
              </div>
              <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-line-200 pt-4">
                {canAdmin ? (
                  <button type="button" onClick={toggleAdminView} aria-pressed={adminMode} className="field-button-secondary">
                    {adminMode ? <Eye className="h-4 w-4" aria-hidden /> : <EyeOff className="h-4 w-4" aria-hidden />}
                    {adminMode ? "Admin view: ON" : "Admin view: OFF"}
                  </button>
                ) : null}
                <div className="ml-auto text-right text-xs">
                  <div className="font-medium text-ink-900">{user.name}</div>
                  <div className="text-ink-500">{roleLabel(user.role)}</div>
                </div>
                <button onClick={handleLogout} className="field-danger">
                  <LogOut className="h-4 w-4" aria-hidden />
                  Logout
                </button>
              </div>
            </motion.div>
          </motion.div>
        ) : null}
      </AnimatePresence>
    </>
  );
}
