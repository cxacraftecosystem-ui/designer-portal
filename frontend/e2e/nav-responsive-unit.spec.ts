import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * The responsive navigation, pinned as source (REQUIREMENTS_2026-10-20 §4). PURE NODE.
 *
 * One breakpoint decides the mode — `lg`, 1024 CSS px — and it is decided by CSS alone: the desktop
 * island carries `max-lg:hidden`, the search header and the bottom island carry `lg:hidden`, and no
 * navigation component reads the window's width. The live twin, `nav-responsive.spec.ts`, drives the
 * same rules in a browser at 390, 820 and 1440 px.
 *
 * It also pins the desktop island's ORIGINAL expanded size — the pill, the logo, the name and the
 * strip — so the island cannot be shrunk again without this file being changed on purpose.
 */

const ROOT = join(__dirname, "..");
const read = (...parts: string[]) => readFileSync(join(ROOT, ...parts), "utf8");

const NAV = read("components", "DynamicIslandNav.tsx");
const BAR = read("components", "nav", "BottomIslandNav.tsx");
const SHEET = read("components", "nav", "IslandMoreSheet.tsx");
const HEADER = read("components", "nav", "AppSearchHeader.tsx");
const PANEL = read("components", "nav", "AppSearchPanel.tsx");
const SCAN = read("components", "scan", "TopBarScanButton.tsx");
const SHELL = read("components", "AppShell.tsx");
const CSS = read("app", "globals.css");

test("the desktop island keeps its original expanded size", () => {
  expect(NAV).toContain('compact ? "px-3 py-1.5" : "px-4 py-2"');
  expect(NAV).toContain('compact ? "h-7 w-7" : "h-8 w-8"');
  expect(NAV).toContain('compact ? "text-sm" : "text-base"');
  expect(NAV).toContain('className="hidden items-center gap-5 overflow-visible whitespace-nowrap pl-2 pr-1 lg:flex"');
});

test("the island is drawn from lg up only, and the phone chrome below lg only", () => {
  expect(NAV).toContain('className="nav-island-frame pointer-events-none fixed inset-x-0 top-3 z-50 flex justify-center max-lg:hidden"');
  expect(BAR).toContain('"bottom-island-frame pointer-events-none fixed inset-x-0 z-50 flex justify-center lg:hidden"');
  expect(HEADER).toMatch(/"app-search-header-frame [^"]*lg:hidden"/);
  expect(SHEET).toContain('"fixed inset-0 z-90 lg:hidden"');
  for (const [name, source] of Object.entries({ NAV, BAR, SHEET, HEADER, PANEL })) {
    expect(source, `${name} must not read the window width`).not.toMatch(/innerWidth|matchMedia/);
  }
});

test("the admin-view control is back to its pill, still a switch, with the bell after it", () => {
  const pill = NAV.indexOf('data-testid="island-admin-view"');
  const bell = NAV.indexOf("<NotificationBell active={isActivePath(\"/notifications\")} />");
  const menu = NAV.indexOf('aria-label="Toggle navigation menu"');
  expect(pill).toBeGreaterThan(0);
  expect(NAV.slice(pill, bell)).toContain("inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1.5 text-xs font-medium");
  expect(NAV.slice(pill, bell)).toContain("<EyeOff");
  expect(NAV.slice(pill, bell)).toContain("<ToggleSwitch");
  expect(NAV.slice(pill, bell)).toContain('label="Admin view"');
  expect(bell).toBeGreaterThan(pill);
  expect(menu).toBeGreaterThan(bell);
  // Offered to every account the provider says has the control (perms adds `available`).
  expect(NAV).toContain("(adminView as { available?: boolean }).available ?? canAdmin");
});

test("the bottom island is Android's: five slots at most, More, a divider and the menu button", () => {
  expect(BAR).toContain('aria-label="Main"');
  expect(BAR).toContain("max-w-[560px] rounded-[28px] border border-line-200 bg-card shadow-island");
  expect(BAR).toContain("splitIsland(islandSlots(roots, groups))");
  expect(BAR).toContain('aria-label="Open menu"');
  expect(BAR).toContain('aria-current={active ? "page" : undefined}');
  expect(BAR).toContain("min-h-12");
  expect(BAR).toContain('style={{ bottom: "calc(0.75rem + env(safe-area-inset-bottom, 0px))" }}');
  // It hides for the keyboard and for open search, never on scroll.
  expect(BAR).toContain("const visible = !hidden && !keyboardOpen;");
  expect(BAR).not.toMatch(/addEventListener\("scroll"/);
});

test("the More sheet is a modal bottom sheet titled More", () => {
  expect(SHEET).toContain('role="dialog"');
  expect(SHEET).toContain('aria-modal="true"');
  expect(SHEET).toContain("aria-label={ISLAND_MORE_LABEL}");
  expect(SHEET).toContain("bg-black/[0.32]");
  expect(SHEET).toContain("max-h-[80dvh] w-full max-w-[560px]");
  expect(SHEET).toContain("rounded-t-[28px]");
  expect(SHEET).toContain('event.key === "Escape"');
});

test("Back closes the More sheet without leaving a step behind", () => {
  // Opening stands the sheet on its own history entry, carrying the router's state with it.
  expect(SHEET).toContain("window.history.pushState({ ...(window.history.state ?? {}), [ISLAND_MORE_HISTORY_KEY]: true }, \"\")");
  // Back (popstate) closes it; a pop onto another sheet entry does not.
  expect(SHEET).toContain("window.addEventListener(\"popstate\", onPopState)");
  expect(SHEET).toMatch(/if \(\(event\.state as Record<string, unknown> \| null\)\?\.\[ISLAND_MORE_HISTORY_KEY\]\) return;\s*popped = true;\s*closeRef\.current\(\);/);
  // Escape or the scrim take the entry off again; a row replaces it with the page it opens.
  expect(SHEET).toContain("if (!popped && !leavingByLink.current && onSheetEntry) window.history.back();");
  expect(SHEET).toMatch(/href=\{href\}\s*replace\s*onClick=\{onNavigate\}/);
  expect(SHEET.match(/onNavigate=\{navigateFromSheet\}/g)?.length).toBe(2);
});

test("the header's scroll delta is taken before the state update, never inside it", () => {
  // Read inside the updater, `now - last` ran after `last = now` whenever React deferred it: every
  // move was zero and the header stayed hidden on the way back up.
  expect(HEADER).toMatch(/const delta = now - last;\s*last = now;\s*setStep\(\(state\) => quickReturnStep\(state, delta, now,/);
  expect(HEADER).not.toContain("quickReturnStep(state, now - last");
});

test("the search header carries the scan button, the name and the search glyph", () => {
  expect(HEADER).toContain("<TopBarScanButton />");
  expect(HEADER).toContain("aria-label={`${appName}. Search`}");
  expect(HEADER).toContain("min-h-[60px]");
  expect(HEADER).toContain("inert={!shown}");
  expect(HEADER).toContain("quickReturnStep(");
  expect(NAV).toContain('const APP_NAME = "Design Workshop";');
});

test("the scan button keeps the frozen signature", () => {
  expect(SCAN).toContain("export function TopBarScanButton({ className }: { className?: string }): JSX.Element");
  expect(SCAN).toContain('aria-label="Scan a code"');
});

test("the search panel follows Android's AppSearchPanel", () => {
  expect(PANEL).toContain("APP_SEARCH_DEBOUNCE_MS = 300");
  expect(PANEL).toContain("UNIFIED_MIN_CHARS");
  expect(PANEL).toContain("pushRecentSearch(");
  expect(PANEL).toContain("Offline — showing what's on this device.");
  expect(PANEL).toContain("router.push(`/search?q=${encodeURIComponent(q)}`)");
});

test("the page clears the header and the bar below lg, and the island from lg up", () => {
  expect(SHELL).toContain(
    'className="relative mx-auto max-w-7xl px-4 pb-[calc(4.5rem+env(safe-area-inset-bottom))] pt-[calc(60px+1.25rem)] lg:pb-12 lg:pt-24"'
  );
});

test("the upload tray and the toasts sit above the bar", () => {
  expect(CSS).toMatch(/@media \(width < 64rem\) \{\s*html\.has-bottom-nav \{\s*--bottom-nav-h: calc\(62px \+ 0\.75rem \+ env\(safe-area-inset-bottom, 0px\)\);/);
  expect(read("components", "media", "UploadTray.tsx")).toContain('bottom: "var(--bottom-nav-h, 0px)"');
  expect(read("components", "ui", "Toast.tsx")).toContain('bottom: "calc(var(--bottom-nav-h, 0px) + 0.75rem)"');
  expect(BAR).toContain('root.classList.add("has-bottom-nav")');
});

test("the notification count reaches the bar from the bell's own store", () => {
  expect(NAV).toContain("useUnreadNotificationCount(Boolean(user))");
  expect(NAV).toContain("badge: item.href === NOTIFICATIONS_HREF ? unreadNotifications : 0");
});
