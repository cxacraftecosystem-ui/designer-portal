"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import {
  Boxes,
  Brush,
  Camera,
  ClipboardCheck,
  ClipboardList,
  DraftingCompass,
  Eye,
  GitBranch,
  Hammer,
  IdCard,
  Images,
  Layers,
  ListTodo,
  LockOpen,
  MapPinned,
  Package,
  PencilRuler,
  Settings,
  Share2,
  Star,
  User as UserIcon,
  UserCog,
  Users,
  UsersRound,
  Wrench,
  type LucideIcon
} from "lucide-react";

import { DashboardCard } from "@/components/DashboardCard";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { StatusBadge } from "@/components/StatusBadge";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import {
  canCreateRecords,
  canDownloadDataset,
  canManageCrafts,
  canManageUsers,
  canManageWorkshops,
  canReview,
  canRunDesignWorkshops,
  isAdmin,
  roleLabel
} from "@/lib/permissions";

/** The five record counters plus the pending backlog — the shape both halves of "At a glance" share. */
type StatTotals = {
  totalArtisans: number;
  totalWorkshops: number;
  totalProductRecords: number;
  totalToolRecords: number;
  totalMediaFiles: number;
  pendingSubmissions: number;
};

type DashboardStats = StatTotals & {
  recentSubmissions: Array<{
    id: string;
    type: string;
    title: string;
    place?: string;
    status: string;
    createdAt: string;
    /** Whose record this is. Absent on an API that predates the repository-wide recent list. */
    createdByName?: string | null;
  }>;
  /**
   * This account's own contribution. Optional because a deployed API may not send it yet, in which
   * case the second row of tiles is simply not drawn — never rendered as a row of zeroes, which
   * would read as "you have contributed nothing".
   */
  mine?: StatTotals;
};

type Tile = {
  label: string;
  icon: LucideIcon;
  newHref: string;
  updateHref?: string;
  /**
   * The primary button's wording. Defaults to "New"; the exceptions are copied verbatim from
   * Android's `EntryMode.createButtonLabel()` so the same tile says the same word in both apps.
   */
  newLabel?: string;
  /**
   * Whether this tile is offered at all. Every tile leads with a "New …" action, so the predicate
   * is the CREATE entitlement for that record type — the same one DynamicIslandNav's NAV_ITEMS use,
   * so the dashboard and the menu can never disagree about what a user may do.
   */
  visible?: boolean;
};

/**
 * Where a "recent submission" row goes when it is clicked.
 *
 * Two shapes, because the app has two ways of editing a record and neither is going away:
 *
 * * artisans, products and tools own a real `/[id]/edit` ROUTE, so the id is a path segment;
 * * workshops, crafts and processes are edited INLINE on their list page, so the id travels as
 *   `?edit=` and `useEditDeepLink` on that page loads it into the form.
 *
 * The three inline types used to return the bare list route and drop the id — clicking a named row
 * in Recent submissions opened the blank CREATE form for its type, which is the same screen the
 * "New" tile opens. (`/processes?edit=` was the exception: it carried the id but nothing on the
 * receiving page read it, so it behaved identically until that page grew the hook.) Interviews are
 * still list-level: an interview is identified by its artisan SET, not by a row id, so
 * `/questionnaire` has no single-record form to deep-link into.
 */
function recordHref(type: string, id: string): string | null {
  switch ((type || "").toLowerCase()) {
    case "artisan":
      return `/artisans/${id}/edit`;
    case "product":
      return `/products/${id}/edit`;
    case "tool":
      return `/tools/${id}/edit`;
    case "process":
      return `/processes?edit=${id}`;
    case "workshop":
      return `/workshops?edit=${id}`;
    case "craft":
      return `/crafts?edit=${id}`;
    case "questionnaire":
    case "interview":
      return "/questionnaire";
    default:
      return null;
  }
}

export default function DashboardPage() {
  // ToastProvider lives in app/layout.tsx — see the note in `ui/Toast`.
  return <DashboardView />;
}

function DashboardView() {
  const { user } = useAuth();
  const { adminMode } = useAdminView();
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiFetch<DashboardStats>("/dashboard/stats")
      .then(setStats)
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load dashboard"));
  }, []);

  // The four core record types share one entitlement (require_record_creator): Researcher and
  // above. A field contributor or volunteer answers existing interviews and adds media instead, so
  // offering them a "New artisan" button would only produce a 403 — the tile is not shown. The
  // Questionnaire and Media tiles below stay for everyone; those are how the lower tiers contribute.
  const creator = canCreateRecords(user);
  /**
   * Admin-tier chrome, matching DynamicIslandNav's `adminSurface`: capability holders below admin
   * (professors, grantees) keep the tile permanently, while an admin — who owns the toggle — sees it
   * only while admin view is ON. The entitlement is checked first, so the toggle can never widen it.
   */
  const adminSurface = (allowed: boolean) => allowed && (!isAdmin(user) || adminMode);

  /**
   * ANDROID `EntryMode` PARITY, STATED HONESTLY AND PER TILE.
   *
   * This comment used to be one line — "same tiles, same order, same labels" — and nothing
   * mechanical has ever checked any of it: `docs/tools/check-docs.mjs` has no opinion about this
   * array, and until this change no spec read it. So the line was a promise on trust, and it was
   * already false in three places before this change. What parity actually requires, tile by tile
   * and by hand:
   *
   *   1. `Tile.label` == Android `EntryMode.label` — the TILE word, which is why the media tile here
   *      says "Miscellaneous Media" and not "Media".
   *   2. `Tile.newLabel` == `EntryMode.createButtonLabel()` — MEDIA "Upload", QUESTIONNAIRE
   *      "New interview", USERS "Manage", every reading surface "Open", otherwise "New".
   *   3. Where one destination has BOTH a tile and a nav row, the tile is Android's
   *      `EntryMode.label` and the nav row is its `EntryMode.actionTitle`. That is usually a noun
   *      against a VERB PHRASE, not a singular against a plural: Artisan/"Record artisan",
   *      Product/"Record product", Process/"Document process", Tool/"Record tool",
   *      Questionnaire/"Take interview", Miscellaneous Media/"Upload media", Craft/"Add craft",
   *      Workshop/"Record workshop", Sharing/"Share data access", Users/"Manage users",
   *      Settings/"Settings hub". Where the `actionTitle` IS the label, the two are identical and
   *      that is correct too: Map, Tasks, View Data, Workshop access, Consolidated questionnaire,
   *      My designer profile.
   *
   *      THE SINGULAR/PLURAL PAIRING IS ONE TILE, NOT THE RULE, and this comment said the opposite
   *      until 2026-08-23. "Design workshop" / "Design workshops" is the only instance of it in the
   *      whole grid, and it is an instance precisely because that destination is NOT an
   *      `EntryMode`: Android draws a bespoke `DesignWorkshopCard` and asserts the pairing by hand
   *      in Kotlin (`DesignWorkshopCardTest`, "the card is singular and the menu row is plural, on
   *      purpose"). Nothing mechanical checks rule 3 on the web side, so the earlier wording was
   *      not merely inert: a reader applying it would have gone and pluralised eleven working nav
   *      rows, or singularised eleven working tiles, against Android.
   *   4. The relative order of the tiles Android DOES have is not reshuffled. Android builds its
   *      grid as `DesignWorkshopCard`, then `EntryMode.entries` in declaration order, then Settings.
   *
   * AND FOUR OF THESE TILES ARE WEB-ONLY, which is the part a bare "parity" claim hides and the
   * reason a future reader must not "restore parity" by deleting one of them. There is no
   * `EntryMode`, no `NavDestination` and no screen at all on the handset for:
   *
   *   • Design workshop — Android draws a bespoke `DesignWorkshopCard`, which is not an `EntryMode`;
   *   • My designer profile — Android has `NavDestination.DESIGNER_PROFILE` and no dashboard card;
   *   • Sketches & prototypes — the TOP-LEVEL ENTRY POINT is web-only. Read the next paragraph
   *     before acting on this line: the FEATURE is on the handset;
   *   • Design review — web-only outright. There is no ratings code anywhere under
   *     `android/app/src/main` (`design-ratings`, `designRatings`, `ratable` all return nothing).
   *
   * The ORDER already diverges too: Android appends its Settings card AFTER Workshop, while Settings
   * sits between Users and Craft here.
   *
   * ─── WHAT ANDROID IS ACTUALLY MISSING FOR SKETCHES, STATED PRECISELY ──────────────────────
   * This comment used to say "the feature does not exist on Android in any form", and that was
   * wrong — wrong in a way this same file contradicts sixty lines further down, where the new
   * tiles' own comment concedes that "uploading a sketch is stage 11 of a workshop". Sketch and
   * prototype work is BUILT on the handset, inside the workshop stage flow:
   * `ui/designworkshop/DwSketchRectifyField.kt` ("Stage 11's `sketch.image` is required…", with its
   * panel on `sketch.lineArtFile`), `data/DwSketchPlate.kt`, `data/DwSketchRectify.kt`,
   * `FieldRenderer.kt`'s `dwOffersSketchRectify`, `ReportFigures.kt:205` counting
   * `"Sketches" to outputCount("SKETCH_DEVELOPMENT", …)`, and `StageSchema.kt:1594` naming
   * "sketch development, prototype iteration" among the stages with no singleton entity.
   *
   * WHAT THE HANDSET LACKS IS THE CHOOSER: no `EntryMode`, no `NavDestination`, hence no dashboard
   * card and no menu row — the only way to a sketch there is to open a workshop first and walk to
   * stage 11. That is exactly what `/sketches-and-prototypes` adds on the web, and it is why each
   * of these pages opens by asking WHICH workshop.
   *
   * The distinction is load-bearing rather than pedantic, because the old sentence was being used to
   * justify the one below it. A maintainer told the handset has no sketches at all will not go
   * looking for Android's existing stage-11 wording to match, and will not recognise the real gap on
   * the handset when it is described to them as "there is no entry point". Both mistakes cost the
   * same thing: two clients that describe one feature in two vocabularies.
   *
   * NO ANDROID-SIDE CHANGE IS IMPLIED BY EITHER TILE, which is a narrower claim than the one this
   * comment used to make and is the one that is true. `EntryMode` and `FIELD_NAV_ITEMS` in the
   * Android tree have no member for either route, so there is no tile, no label and no
   * `createButtonLabel()` on that side for these two to agree WITH — nothing to copy and nothing to
   * check. If Android ever grows the chooser, rules 1 and 2 above start applying to these two tiles
   * like any other, and the strings to match will be that new `EntryMode`'s.
   * Android missing a top-level entry point the web has built is a product gap on the handset, not
   * parity debt this array created, and the repository's own new-page checklist reads
   * one-directionally for exactly this case: "a dashboard tile IF ANDROID HAS ONE"
   * (.claude/skills/field-repo-frontend/SKILL.md, "New page").
   */
  const tiles: Tile[] = [
    // FIRST, and deliberately. This is the product: a designer opens the app to run a design and
    // prototype workshop, and everything below it — artisans, products, tools, the questionnaire —
    // is supporting reference data that a workshop draws on. The tile order used to be inherited
    // wholesale from the repository app this was built from, so the one thing the app exists for
    // was not on the dashboard at all and could be reached only by typing the URL.
    {
      label: "Design workshop",
      // The nav entry's and the page header's own icon, not `Layers` — which the Consolidated
      // questionnaire tile further down also uses, so the grid carried the same glyph twice under
      // two different words. Each client is internally consistent instead: DraftingCompass here and
      // in DynamicIslandNav, `Icons.Filled.DesignServices` on both of Android's.
      icon: DraftingCompass,
      newHref: "/design-workshops?new=1",
      updateHref: "/design-workshops",
      // `canRunDesignWorkshops` and NOT `creator`, which is what this line used to say. The two
      // differ for a RESEARCHER and a PROFESSOR, and both were being shown a tile whose every
      // destination is `ROUTE_GUARDS`' "Designer access required" panel (lib/permissions.ts:277-283).
      // It is a SET, {DESIGNER, ADMIN, MASTER_ADMIN}, so a professor outranks a designer and is
      // still outside it; Android's card reads the same predicate (`DesignWorkshopCard.visibleTo`).
      //
      // WHAT THE SERVER ACTUALLY REFUSES, stated exactly, because getting this wrong in either
      // direction is how this repository's two shipped security bugs happened. `_require_designer`
      // is on the WRITES and only on the writes — POST /design-workshops
      // (backend/app/api/routes/design_workshops.py:392), PATCH (:439), PUT stage (:539), and the
      // set the server's own test enumerates (tests/test_design_workshop_gate.py:66). The LIST
      // (`list_design_workshops`, :304) and the reads below it take `get_current_user` alone and
      // scope rows with `visible_to_clause`. So this tile mirrors ROUTE_GUARDS — a deliberate UI
      // narrowing over an open read — and NOT a refusal the API would make. Widen the tile and you
      // have widened nothing but the browser; narrow the API and narrow this line with it.
      visible: canRunDesignWorkshops(user),
      newLabel: "New workshop"
    },
    // ── THE OTHER TWO FACES OF THE FORTNIGHT ABOVE ───────────────────────────────────────────────
    //
    // BOTH OF THESE PAGES WERE FINISHED, GUARDED, LINKED IN THE NAV SHEET AND LIVE, AND THE OWNER
    // REPORTED THE FEATURE AS "STILL NOT THERE". Nothing was broken, which is why it is worth
    // naming exactly: `/sketches-and-prototypes` and `/design-review` each had a `NAV_ITEMS` entry,
    // a `ROUTE_GUARDS` row and its twin row in docs/PERMISSIONS.md §5, and both rendered for
    // the accounts entitled to them. What neither had was a tile. This grid is where this product's
    // users look — it is the whole of what Android's dashboard is, and it is the screen the app
    // opens on — whereas the nav sheet is a SHEET: behind a tap, one scrolling column of every
    // destination the account qualifies for, and a designer who does not already know a feature
    // exists has no reason to open it hunting for something they have never heard of. `Map` and
    // `Consolidated questionnaire` further down arrived by precisely this route, and
    // e2e/feature-entry-points.spec.ts opens with the sentence this comment is the second instance
    // of: a feature a researcher cannot find is a feature that was not built.
    //
    // SECOND AND THIRD, DIRECTLY BEHIND "Design workshop", and not merely because new tiles land at
    // the top. Three reasons:
    //
    //   • They are the same work. Uploading a sketch is stage 11 of a workshop and ranking it in the
    //     pool is the round that follows; these two routes are those two things reached with NO
    //     workshop id in hand, which is why each page's first question is which workshop. They are
    //     not reference data like Artisan / Product / Process / Tool below, and they are not one of
    //     the three "show me what is already in the repository" reading surfaces that View Data, Map
    //     and Consolidated questionnaire form.
    //   • They carry the IDENTICAL predicate to the tile above, so all three appear and disappear
    //     together and a reader verifies that by reading three adjacent lines rather than scanning
    //     sixty. Whoever next widens or narrows `canRunDesignWorkshops` sees every call site at once.
    //   • The grid row they cost is charged only to the accounts that can see them at all, which is
    //     the same trade Android's own `DesignWorkshopCard` comment makes for going first.
    //
    // `canRunDesignWorkshops` AND NOTHING ELSE. The tile above spells out why at length; the short
    // version is that it is a SET, {DESIGNER, ADMIN, MASTER_ADMIN}, and not a rank threshold, so a
    // PROFESSOR sits outside it while outranking a designer everywhere else in the app. Both of
    // these paths answer a professor with `ROUTE_GUARDS`' "Designer access required" panel, so
    // `creator` here — the mistake this very array has already shipped once, on the tile above —
    // would offer a researcher and a professor a tile whose only destination is a refusal.
    //
    // NOT `adminSurface` EITHER, deliberately. Neither nav entry is flagged as admin chrome and
    // neither path is in `ADMIN_CHROME_ROUTES`, so wrapping these would hide, from an admin who has
    // admin view switched OFF, a page that admin may still open — a link removed from a working
    // route, which is the defect the /review nav entry's comment records having already caused.
    //
    // "Open" AND NOT "New", which is a correctness check twice over rather than a matter of taste.
    // `DashboardCard` picks `ArrowRight` over `Plus` off this exact word, and arriving at either
    // page creates nothing — both open a chooser. And a plus would be wrong a second time: bringing
    // a workshop into existence is `canCreateDesignWorkshops`, a STRICT SUBSET that REFUSES a
    // DESIGNER, i.e. most of the accounts these two tiles exist for.
    //
    // THE LABELS ARE COPIED CHARACTER FOR CHARACTER OUT OF `NAV_ITEMS` — ampersand, lower-case "p"
    // and all — and that is load-bearing rather than tidy. This destination already answers to three
    // spellings in the tree: the nav label "Sketches & prototypes", the page title "Sketches and
    // Prototypes", and the guard panel's "Designer access required". A fourth invented here would be
    // found by nobody's grep. AND THAT IS RULE 3 OF THE PARITY NOTE ABOVE BEING FOLLOWED, not
    // waived, which is what this comment claimed before 2026-08-23. Rule 3 pairs a tile with its nav
    // row through Android's `EntryMode.label` and `EntryMode.actionTitle`; neither of these two
    // destinations is an `EntryMode` at all, so there is no second string to differ from and the
    // tile takes the nav row's own label verbatim. The one place in this grid where the tile and the
    // row genuinely differ by number is Design workshop / Design workshops, which is a one-off
    // Android asserts in Kotlin for its bespoke card and is not a pattern to imitate here.
    //
    // The icons are each destination's own nav glyph, and neither `PencilRuler` nor `Star` appears
    // anywhere else in this grid, so the one-glyph-per-meaning-per-client rule the Design workshop
    // tile states still holds. `Star` and not `Globe2`: the design-review PAGE header draws `Globe2`
    // while its nav entry draws `Star`, and where a page and its menu row disagree the TILE FOLLOWS
    // THE MENU — the invariant this file keeps is that the dashboard and the menu never disagree
    // about a destination, and the page header is not part of that pair.
    {
      label: "Sketches & prototypes",
      icon: PencilRuler,
      newHref: "/sketches-and-prototypes",
      newLabel: "Open",
      visible: canRunDesignWorkshops(user)
    },
    {
      label: "Design review",
      icon: Star,
      newHref: "/design-review",
      newLabel: "Open",
      visible: canRunDesignWorkshops(user)
    },
    { label: "Artisan", icon: UserIcon, newHref: "/artisans/new", updateHref: "/artisans", visible: creator },
    { label: "Product", icon: Package, newHref: "/products/new", updateHref: "/products", visible: creator },
    { label: "Process", icon: GitBranch, newHref: "/processes?new=1", updateHref: "/processes", visible: creator },
    { label: "Tool", icon: Wrench, newHref: "/tools/new", updateHref: "/tools", visible: creator },
    // Answering an interview and uploading media are open to every signed-in user — they are how a
    // volunteer contributes.
    { label: "Questionnaire", icon: ClipboardList, newHref: "/questionnaire?new=1", updateHref: "/questionnaire", newLabel: "New interview" },
    { label: "Miscellaneous Media", icon: Images, newHref: "/media", newLabel: "Upload" },
    // Reading is never gated: without dataset access the tile leads to Browse records instead.
    { label: "View Data", icon: Eye, newHref: canDownloadDataset(user) ? "/data" : "/search", newLabel: "Open" },
    // The two web-only reading surfaces, which had no entry point anywhere and were reachable only
    // by typing the URL. They sit here, after View Data, because all three answer "show me what is
    // already in the repository" — and a feature a researcher cannot find is a feature that was not
    // built. Both are open to any signed-in user; the map filters its pins per viewer on the server.
    { label: "Map", icon: MapPinned, newHref: "/map", newLabel: "Open" },
    {
      label: "Consolidated questionnaire",
      icon: Layers,
      newHref: "/questionnaire/consolidated",
      newLabel: "Open"
    },
    // Tasks and Workshop access are dashboard tiles on Android and were menu-only here, which is
    // the difference between a new researcher finding "how do I get into this workshop" and not.
    { label: "Tasks", icon: ListTodo, newHref: "/tasks", newLabel: "Open" },
    { label: "Sharing", icon: Share2, newHref: "/sharing" },
    // Ungated, and now honestly so: the destination forks on the role, opening the admin console
    // only for an admin in admin view and the account's own request page for everyone else. It used
    // to point straight at the console, so this tile — shown to all — was a padlock for most of them.
    { label: "Workshop access", icon: LockOpen, newHref: "/workshop-access", newLabel: "Open" },
    // The designer's own standing details, typed once instead of into stage 1 and stage 3 of every
    // workshop. "Open" and not "New": the row is created empty by the GET itself, so there is never
    // a profile to create — a plus on this button would be a lie, and DashboardCard picks its icon
    // from exactly this word.
    {
      label: "My designer profile",
      icon: IdCard,
      newHref: "/designers/profile",
      newLabel: "Open",
      // The same predicate as the nav entry, so the dashboard and the menu can never disagree about
      // what this account may do. Not admin chrome: it is the person's own record, and an admin
      // browsing as an ordinary user still has a profile of their own to fill in.
      visible: canRunDesignWorkshops(user)
    },
    { label: "Users", icon: UserCog, newHref: "/users", visible: adminSurface(canManageUsers(user)), newLabel: "Manage" },
    // NO DESIGNER ROSTER TILE HERE, deliberately. It lives in the settings hub (/admin) and
    // nowhere else. The roster is a list of named individuals and their institutional standing —
    // administrative configuration, not something anybody does day to day — and it was previously
    // reachable from three places at once: this dashboard, the nav menu, and the hub. Three
    // entrances to one admin screen is three things to keep gated in step, and the dashboard is
    // where a designer looks for their WORK, not for the panel that decides who is empanelled.
    { label: "Settings", icon: Settings, newHref: "/admin", visible: adminSurface(isAdmin(user)), newLabel: "Open" },
    { label: "Craft", icon: Brush, newHref: "/crafts?new=1", updateHref: "/crafts", visible: canManageCrafts(user) },
    {
      label: "Workshop",
      icon: UsersRound,
      newHref: "/workshops?new=1",
      updateHref: "/workshops",
      visible: canManageWorkshops(user)
    }
  ];

  /**
   * Every total is a question ("which 74 tools?"), and a number you cannot click is a dead end.
   * Each card opens the search view already filtered to that record type — `?type=` is read by
   * app/(protected)/search — so the count and the list behind it can never disagree.
   *
   * Pending review is the exception: it opens the review queue, and only for someone who may
   * actually act on it. A researcher who cannot review still SEES the backlog (it tells them
   * their own submissions are waiting) but the card does not pretend to be a door they can open.
   *
   * THESE ARE THE REPOSITORY'S TOTALS, and saying so out loud is the point. They used to be the
   * signed-in researcher's own upload counts wearing these labels, because the API filtered every
   * total by ownership — so two people standing side by side read different numbers off the word
   * "Artisans", and somebody who had not uploaded yet read six zeroes and concluded the repository
   * was empty. Reading is open now; the caller's own contribution is the second row below, asked for
   * explicitly and labelled as theirs.
   */
  const statCards = stats
    ? [
        { label: "Artisans", value: stats.totalArtisans, icon: Users, href: "/search?type=artisans" },
        { label: "Workshops", value: stats.totalWorkshops, icon: MapPinned, href: "/search?type=workshops" },
        { label: "Products", value: stats.totalProductRecords, icon: Boxes, href: "/search?type=products" },
        { label: "Tools", value: stats.totalToolRecords, icon: Hammer, href: "/search?type=tools" },
        { label: "Media files", value: stats.totalMediaFiles, icon: Camera, href: "/search?type=media" },
        {
          label: "Pending review",
          value: stats.pendingSubmissions,
          icon: ClipboardCheck,
          href: canReview(user) ? "/review" : null
        }
      ]
    : [];

  /**
   * The same six counters for THIS account's own work, and every one of them opens My Activity —
   * which now asks the API for `createdBy=<me>` rather than sifting page one of the repository, so
   * the number and the list behind it agree again.
   *
   * Drawn only when the API actually sent a `mine` block. A row of zeroes synthesised from a missing
   * field would be a lie in exactly the direction this whole change exists to fix.
   */
  const mineCards =
    stats?.mine
      ? [
          { label: "Artisans", value: stats.mine.totalArtisans, icon: Users },
          { label: "Workshops", value: stats.mine.totalWorkshops, icon: MapPinned },
          { label: "Products", value: stats.mine.totalProductRecords, icon: Boxes },
          { label: "Tools", value: stats.mine.totalToolRecords, icon: Hammer },
          { label: "Media files", value: stats.mine.totalMediaFiles, icon: Camera },
          { label: "Awaiting review", value: stats.mine.pendingSubmissions, icon: ClipboardCheck }
        ]
      : null;

  return (
    <>
      {/* The dashboard is the navigation root — it never shows a back button. */}
      <PageHeader title="What would you like to do?" back={false} />
      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{error}</div>
      ) : null}
      {/* The tiles are glass, and glass on a flat canvas refracts nothing you can see — these
          two soft orbs are what their rims bend. Purple only: `grad-mesh` carries a faint amber
          orb, and gold belongs to the marketing surfaces, never to a data screen. */}
      <div className="relative">
        <div aria-hidden className="pointer-events-none absolute -inset-x-6 -inset-y-8 overflow-hidden">
          <div className="absolute -left-12 -top-4 h-72 w-72 rounded-full bg-purple-300/25 blur-3xl" />
          <div className="absolute -right-8 bottom-0 h-80 w-80 rounded-full bg-purple-400/20 blur-3xl" />
        </div>
        <div className="relative grid grid-cols-2 gap-3 md:grid-cols-3">
          {tiles
            .filter((tile) => tile.visible !== false)
            .map((tile) => (
              <DashboardCard
                key={tile.label}
                label={tile.label}
                icon={tile.icon}
                newHref={tile.newHref}
                updateHref={tile.updateHref}
                newLabel={tile.newLabel}
              />
            ))}
        </div>
      </div>

      {/* A short grid is otherwise unexplained: say WHY the record tiles are missing and where the
          tier comes from, rather than leaving a volunteer to assume the app is broken. */}
      {!creator ? (
        <p className="mt-4 rounded-md border border-line-200 bg-surface-50 px-4 py-3 text-sm leading-6 text-ink-500">
          You are signed in as <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>. That covers
          answering existing interviews, uploading media and commenting — find an entry through{" "}
          <Link href="/search" className="font-medium text-purple-700 underline-offset-2 hover:underline">
            Browse records
          </Link>{" "}
          and add to it. Opening a new artisan, product, process or tool needs Researcher access — ask an admin to
          raise your tier.{" "}
          <Link href="/guide" className="font-medium text-purple-700 underline-offset-2 hover:underline">
            Open the walkthrough
          </Link>
          .
        </p>
      ) : null}

      <section className="mt-8">
        <div className="mb-3 flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
          <h2 className="font-display text-lg font-bold text-ink-900">At a glance</h2>
          <p className="text-xs text-ink-500">Everything in the repository, not only your own entries.</p>
        </div>
        {!stats && !error ? (
          <div className="panel p-4 text-sm text-ink-500">Loading...</div>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {statCards.map((card) => {
              const body = (
                <>
                  <div className="flex items-center justify-between gap-3">
                    <div className="text-sm font-medium text-ink-500">{card.label}</div>
                    <div className="grid h-9 w-9 place-items-center rounded-md bg-purple-50 text-purple-700">
                      <card.icon className="h-[18px] w-[18px]" aria-hidden />
                    </div>
                  </div>
                  <div className="mt-3 font-display text-3xl font-bold text-ink-900">{card.value}</div>
                </>
              );
              return card.href ? (
                <Link
                  key={card.label}
                  href={card.href}
                  aria-label={`${card.label}: ${card.value}. Open the full list.`}
                  className="panel block p-4 transition-shadow hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-purple-700"
                >
                  {body}
                </Link>
              ) : (
                <div className="panel p-4" key={card.label}>
                  {body}
                </div>
              );
            })}
          </div>
        )}
      </section>

      {/* The caller's own contribution, in the SAME six counters and the same order, so the two rows
          read as one comparison rather than as two unrelated grids. Denser than the row above on
          purpose: it is the secondary question, and every tile leads to the same place. */}
      {mineCards ? (
        <section className="mt-6">
          <div className="mb-3 flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
            <h2 className="font-display text-lg font-bold text-ink-900">Your contribution</h2>
            <Link
              href="/activity"
              className="text-xs font-semibold text-purple-700 underline-offset-2 hover:underline"
            >
              Open My Activity
            </Link>
          </div>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
            {mineCards.map((card) => (
              <Link
                key={card.label}
                href="/activity"
                aria-label={`Your ${card.label.toLowerCase()}: ${card.value}. Open My Activity.`}
                className="panel block p-3 transition-shadow hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-purple-700"
              >
                <div className="flex items-center gap-2">
                  <card.icon className="h-4 w-4 shrink-0 text-purple-700" aria-hidden />
                  <span className="min-w-0 truncate text-xs font-medium text-ink-500">{card.label}</span>
                </div>
                <div className="mt-2 font-display text-2xl font-bold text-ink-900">{card.value}</div>
              </Link>
            ))}
          </div>
        </section>
      ) : null}

      <section className="mt-6 panel overflow-hidden">
        <div className="border-b border-line-200 px-4 py-3">
          <h2 className="font-display font-bold text-ink-900">Recent submissions</h2>
          <p className="mt-0.5 text-xs text-ink-500">
            The newest entries across the repository, whoever filed them.
          </p>
        </div>
        {!stats ? (
          <div className="p-4 text-sm text-ink-500">Loading...</div>
        ) : stats.recentSubmissions.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No submissions yet" body="New field documentation will appear here after records are created." />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[720px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <th className="px-4 py-3">Title</th>
                  <th className="px-4 py-3">Type</th>
                  <th className="px-4 py-3">Place</th>
                  {/* The list is the whole repository now, so an unattributed row is a row nobody
                      can follow up on. */}
                  <th className="px-4 py-3">Recorded by</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3">Created</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {stats.recentSubmissions.map((item) => (
                  <tr key={`${item.type}-${item.id}`} className="hover:bg-surface-50">
                    <td className="px-4 py-3 font-medium text-ink-900">
                      {/* The row a researcher recognises is the one they want to correct, so the
                          title is the link — straight into the edit view for that record type. */}
                      {recordHref(item.type, item.id) ? (
                        <Link
                          href={recordHref(item.type, item.id)!}
                          className="text-purple-700 underline-offset-2 hover:underline"
                        >
                          {item.title}
                        </Link>
                      ) : (
                        item.title
                      )}
                    </td>
                    <td className="px-4 py-3 capitalize text-ink-700">{item.type}</td>
                    <td className="px-4 py-3 text-ink-700">{item.place ?? "-"}</td>
                    <td className="px-4 py-3 text-ink-700">{item.createdByName || "-"}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={item.status} />
                    </td>
                    <td className="px-4 py-3 text-ink-700">{formatDateTime(item.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
