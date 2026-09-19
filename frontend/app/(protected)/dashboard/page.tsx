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
  FileSpreadsheet,
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
  QrCode,
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
import { MinistryDeskCard } from "@/components/dashboard/MinistryDeskCard";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { StatusBadge } from "@/components/StatusBadge";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import {
  canCreateRecords,
  canCreateWorkshops,
  canDownloadDataset,
  canManageCrafts,
  canManageUsers,
  canManageWorkshops,
  canReview,
  canRunDesignWorkshops,
  canSeeDataTile,
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
  /**
   * Where "New" goes, and OPTIONAL: a tile may offer Update and no New. See `DashboardCard`, which
   * carries the case that made it so — a professor may correct a workshop and may no longer open
   * one, so drawing both buttons offered them a form they would be refused.
   */
  newHref?: string;
  updateHref?: string;
  /**
   * The primary button's wording. Defaults to "New"; the exceptions are copied verbatim from
   * Android's `EntryMode.createButtonLabel()` so the same tile says the same word in both apps.
   */
  newLabel?: string;
  /**
   * Whether this tile is offered at all — the predicate for REACHING this record type, which is the
   * same one DynamicIslandNav's NAV_ITEMS use, so the dashboard and the menu can never disagree
   * about where a user may go.
   *
   * IT USED TO SAY "the CREATE entitlement", AND THAT STOPPED BEING TRUE OF ONE TILE. Every tile did
   * lead with a "New …" action until the workshop create moved to the ministry floor; the Workshop
   * tile now leads with Update for a professor, who may reach and correct a workshop and may not
   * open one. Where the two halves differ, this stays the WIDER predicate and the create half is
   * expressed by omitting `newHref` — hiding the tile would take away an Update somebody has.
   */
  visible?: boolean;
  /**
   * WHICH MEGA CARD THIS TILE IS DRAWN IN. A scalar on the tile, and that shape is forced.
   *
   * ── WHY THE GROUPING IS NOT NESTED ARRAYS, WHICH IS THE OBVIOUS WAY TO WRITE IT ───────────────
   *
   * THIS ARRAY IS READ AS TEXT BY THREE PARSERS IN THREE LANGUAGES, and every one of them assumes
   * the same two things: that it begins with the exact declaration written below, and that every
   * top-level element is a single object literal whose `label` is a double-quoted string.
   *
   * ⚠ AND THAT DECLARATION MAY NOT BE QUOTED IN PROSE ANYWHERE ABOVE IT. All three parsers find
   * the array with a plain `indexOf` over the RAW file and only strip comments afterwards, so a
   * comment repeating the declaration is found FIRST and the scan then runs off the end of the file
   * looking for a closing bracket. The TypeScript parser fails with "the `tiles` array literal is
   * not closed" — which names the array and not the sentence, and sends the next reader looking for
   * an unbalanced brace that does not exist. (Measured: this paragraph did exactly that when it was
   * first written.)
   *
   *   · `frontend/e2e/dashboard-tile-parity-unit.spec.ts` — TypeScript. Its `tileArrayBody` scans
   *     from that exact declaration and `splitTop` splits on depth-zero commas; a nested array would
   *     parse as one element with an empty label and be DROPPED, taking `TILES.length > 15` with it
   *     and failing every assertion in the file with a misleading "the tile is missing" story.
   *   · `android/.../DashboardTileParityTest.kt` — Kotlin, reaching out of `android/` into
   *     `frontend/`. It asserts every element `startsWith("{")`, that the web and the handset hold
   *     the SAME labels in the SAME order with Settings the one exception, and that the first four
   *     are Design workshop · Sketches & prototypes · Design review · Artisan.
   *   · `backend/tests/test_annual_plan_web_surface.py` — Python, asserting an ABSENCE.
   *
   * So the ORDER of this array is pinned on both clients and may not move. The grouping is therefore
   * a property ON each tile and the regrouping happens at RENDER time: the array is unchanged, and
   * `TILE_GROUPS` below decides what the reader sees. A tile's group may be edited freely; its
   * position may not.
   *
   * OPTIONAL, AND AN OMITTED GROUP IS A DECISION THE RENDERER REFUSES TO MAKE FOR YOU. A tile with
   * no group falls into `Miscellaneous` and the grid still draws it — nothing disappears — because
   * the one thing worse than a tile in the wrong mega card is a tile in none of them, which is this
   * repository's silent-emptiness bug wearing a layout change.
   */
  group?: TileGroup;
};

/**
 * THE FOUR MEGA CARDS, in render order. Owner ruling, 2026-09-20: the grid had grown to twenty-one
 * tiles and "classify all the myriad number of pages that we have currently into multiple sections".
 *
 * ── THE NAMES ARE THE OWNER'S AND THE ORDER IS THE PRODUCT'S ─────────────────────────────────────
 *
 * For Designers leads for the same reason the Design workshop tile leads the array: it is what this
 * app is for, and everything under Records is reference data a workshop draws on. Admin sits last
 * because it is configuration rather than work — the same place the nav's own `NAV_GROUPS` puts it.
 *
 * ── THEY ARE NOT `NAV_GROUPS`, AND THE OVERLAP IS A COINCIDENCE WORTH NOT BUILDING ON ────────────
 *
 * `NAV_GROUPS` is `["Record", "Browse", "Admin", "Account"]` and two of these four share a word with
 * it. Deriving one from the other would be wrong in both directions: the menu files Design review
 * and Sketches & prototypes under Browse (they are reading surfaces reached without a workshop in
 * hand) while the grid files them with the workshop they belong to, and the menu has an Account
 * group the grid has no tile for. Two registers, two jobs — the same reason `NAV_ITEMS` and this
 * array are two lists at all.
 */
const TILE_GROUPS = [
  {
    id: "designers" as const,
    title: "For designers",
    note: "Running a design & prototype workshop, and the two halves of it reached without one in hand."
  },
  {
    id: "records" as const,
    title: "Records",
    note: "The repository a workshop draws on — the people, the things they make, and how they make them."
  },
  {
    id: "misc" as const,
    title: "Miscellaneous",
    note: "Reading what is already recorded, and the errands around it."
  },
  {
    id: "admin" as const,
    title: "Admin",
    note: "Who may do what, and how this deployment is configured."
  }
];

type TileGroup = (typeof TILE_GROUPS)[number]["id"];

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
   * AND FOUR OF THESE TILES HAVE NO ANDROID *CARD*, which is the part a bare "parity" claim hides
   * and the reason a future reader must not "restore parity" by deleting one of them. What each is
   * missing is NOT the same thing, and two of the four lines below said the wrong thing until
   * 2026-08-26 — so read the distinction rather than the count:
   *
   *   • Design workshop — Android draws a bespoke `DesignWorkshopCard`, which is not an `EntryMode`;
   *   • My designer profile — Android has `NavDestination.DESIGNER_PROFILE` and no dashboard card;
   *   • Sketches & prototypes — Android now has BOTH the feature and a top-level entry point
   *     (`NavDestination.SKETCHES_AND_PROTOTYPES`, `SketchesAndPrototypesScreen.kt`). What it has no
   *     member for is `EntryMode`, i.e. the dashboard card. Read the next paragraph before acting on
   *     this line;
   *   • Design review — SAME SHAPE AS THE LINE ABOVE, and this line used to be the most wrong in the
   *     file. It said "web-only outright. There is no ratings code anywhere under
   *     `android/app/src/main`". That was true when it was written and false from the moment the
   *     handset grew the feature: `NavDestination.DESIGN_REVIEW` + its `NavEntry`
   *     (`ui/AppNavigation.kt`), `DesignReviewScreen.kt`, `DwRankableList.kt`,
   *     `data/DwDesignRatings.kt`, all three `/design-ratings` endpoints bound in
   *     `WorkshopRepositoryApi.kt`, an `OFFLINE_DESIGN_RATING` outbox kind, and seven wiring points
   *     in `MainActivity.kt`. What is missing is the `EntryMode` — the dashboard card — and nothing
   *     else.
   *
   * ⚠ THE LESSON THIS LINE IS NOW THE EXAMPLE OF: a "does not exist on the other client" claim goes
   * stale silently and in the WORST direction. Nothing fails, nobody is told, and the next reader
   * either hunts a gap that has been closed or, far worse, deletes a tile believing the other client
   * never had it. If you are about to write another such sentence, write the GREP that settles it
   * beside the claim — the way `e2e/dashboard-tile-parity-unit.spec.ts` does — so a reader can
   * re-check it in one command instead of trusting prose.
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
      newLabel: "New workshop",
      group: "designers"
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
      visible: canRunDesignWorkshops(user),
      group: "designers"
    },
    {
      label: "Design review",
      icon: Star,
      newHref: "/design-review",
      newLabel: "Open",
      visible: canRunDesignWorkshops(user),
      group: "designers"
    },
    { label: "Artisan", icon: UserIcon, newHref: "/artisans/new", updateHref: "/artisans", visible: creator, group: "records" },
    { label: "Product", icon: Package, newHref: "/products/new", updateHref: "/products", visible: creator, group: "records" },
    { label: "Process", icon: GitBranch, newHref: "/processes?new=1", updateHref: "/processes", visible: creator, group: "records" },
    { label: "Tool", icon: Wrench, newHref: "/tools/new", updateHref: "/tools", visible: creator, group: "records" },
    // Answering an interview and uploading media are open to every signed-in user — they are how a
    // volunteer contributes.
    { label: "Questionnaire", icon: ClipboardList, newHref: "/questionnaire?new=1", updateHref: "/questionnaire", newLabel: "New interview", group: "records" },
    { label: "Miscellaneous Media", icon: Images, newHref: "/media", newLabel: "Upload", group: "records" },
    /*
      SCAN A CODE — added 2026-08-28 on the owner's report that scanning was "buried underneath a
      lot of pages", which it was: `RecordCodeScanPanel` was mounted above the search box on
      `/search` and nowhere else, so reading a tag meant opening a destination named after reading a
      LIST and then noticing a panel above the box. There was no row and no tile anywhere named
      after the action.

      IT SITS HERE, AT THE HEAD OF THE THREE READING SURFACES, and not inside the design-workshop
      block above. The block's own assertion in
      `frontend/e2e/dashboard-tile-parity-unit.spec.ts` exists to stop it being "padded from below"
      by a tile that is not a member, and this is not a member: a scan is repository-wide and knows
      nothing about which workshop anybody is standing in. Its NAV ROW is in the Browse group beside
      Browse records, Map and View Data, so the grid neighbourhood and the menu group agree — which
      is the property worth keeping, and the one a placement chosen purely for prominence would have
      broken.

      "Open" AND NOT "New": arriving here creates nothing, and `DashboardCard` reads exactly this
      word to draw an arrow instead of a plus.

      UNGATED, matching its nav entry and `/search` — see the page's own header for why a guard here
      would be a client-side rule the API does not have.
    */
    { label: "Scan a code", icon: QrCode, newHref: "/scan", newLabel: "Open", group: "misc" },
    /*
      THE DESTINATION FORKS ON THE GRANT; WHETHER THE TILE IS DRAWN AT ALL FORKS ON THE TIER, AND
      THOSE ARE TWO DIFFERENT QUESTIONS.

      `canDownloadDataset(user) ? "/data" : "/search"` has always been right about WHERE: a reader
      without dataset access is sent to Browse records rather than at a padlock. What was missing was
      any answer to WHETHER — the tile was drawn for every signed-in account, including the tiers the
      "View Data" MENU ROW has always refused (`NAV_ITEMS` gates it on `canDownloadDataset`), so the
      dashboard and the menu disagreed outright about one destination. The owner reported it from the
      designer's side: "for designers, view data card should not be there, it is only for admins,
      master admins, professors, and researchers."

      `canSeeDataTile` IS THOSE FOUR TIERS AND IS A SET, NOT A RANK FLOOR — DESIGNER(35) and
      INSPECTOR(37) sit inside the Researcher-and-above range and are both out. Its own doc block
      carries the argument, including why a designer holding an explicit dataset grant keeps the menu
      row and the URL and still gets no tile.

      NOTHING HERE IS A ROUTE GUARD. `/data` keeps `canDownloadDataset`; `/search` stays open to
      every signed-in account because its endpoints are, and `ROUTE_GUARDS` is unchanged.
    */
    {
      label: "View Data",
      icon: Eye,
      newHref: canDownloadDataset(user) ? "/data" : "/search",
      newLabel: "Open",
      visible: canSeeDataTile(user),
      group: "misc"
    },
    // The two web-only reading surfaces, which had no entry point anywhere and were reachable only
    // by typing the URL. They sit here, after View Data, because all three answer "show me what is
    // already in the repository" — and a feature a researcher cannot find is a feature that was not
    // built. Both are open to any signed-in user; the map filters its pins per viewer on the server.
    { label: "Map", icon: MapPinned, newHref: "/map", newLabel: "Open", group: "misc" },
    {
      label: "Consolidated questionnaire",
      icon: Layers,
      newHref: "/questionnaire/consolidated",
      newLabel: "Open",
      group: "misc"
    },
    // Tasks and Workshop access are dashboard tiles on Android and were menu-only here, which is
    // the difference between a new researcher finding "how do I get into this workshop" and not.
    { label: "Tasks", icon: ListTodo, newHref: "/tasks", newLabel: "Open", group: "misc" },
    { label: "Sharing", icon: Share2, newHref: "/sharing", group: "misc" },
    // Ungated, and now honestly so: the destination forks on the role, opening the admin console
    // only for an admin in admin view and the account's own request page for everyone else. It used
    // to point straight at the console, so this tile — shown to all — was a padlock for most of them.
    { label: "Workshop access", icon: LockOpen, newHref: "/workshop-access", newLabel: "Open", group: "misc" },
    // The designer's own standing details, typed once instead of into stage 1 and stage 3 of every
    // workshop. "Open" and not "New": the row is created empty by the GET itself, so there is never
    // a profile to create — a plus on this button would be a lie, and DashboardCard picks its icon
    // from exactly this word.
    {
      label: "My designer profile",
      icon: IdCard,
      newHref: "/designers/profile",
      newLabel: "Open",
      group: "designers",
      // The same predicate as the nav entry, so the dashboard and the menu can never disagree about
      // what this account may do. Not admin chrome: it is the person's own record, and an admin
      // browsing as an ordinary user still has a profile of their own to fill in.
      visible: canRunDesignWorkshops(user)
    },
    { label: "Users", icon: UserCog, newHref: "/users", visible: adminSurface(canManageUsers(user)), newLabel: "Manage", group: "admin" },
    // NO DESIGNER ROSTER TILE HERE, deliberately. It lives in the settings hub (/admin) and
    // nowhere else. The roster is a list of named individuals and their institutional standing —
    // administrative configuration, not something anybody does day to day — and it was previously
    // reachable from three places at once: this dashboard, the nav menu, and the hub. Three
    // entrances to one admin screen is three things to keep gated in step, and the dashboard is
    // where a designer looks for their WORK, not for the panel that decides who is empanelled.
    { label: "Settings", icon: Settings, newHref: "/admin", visible: adminSurface(isAdmin(user)), newLabel: "Open", group: "admin" },
    { label: "Craft", icon: Brush, newHref: "/crafts?new=1", updateHref: "/crafts", visible: canManageCrafts(user), group: "records" },
    {
      // TWO PREDICATES ON ONE TILE, since 2026-09-16, and the split is the ruling rather than a
      // refinement. Opening a workshop is the ministry's act (`require_workshop_opener`, a
      // MINISTRY_ADMIN floor); correcting one somebody else opened is still Professor and above. A
      // single `visible: canManageWorkshops` left a professor with a "New" button that landed on the
      // explanatory panel instead of a form — the right answer, one wasted click and one moment of
      // believing the app was broken. `newVisible` is what the tile takes to say which half is
      // theirs; `updateHref` stays on the wider predicate, because Edit is exactly what a professor
      // still has.
      label: "Workshop",
      icon: UsersRound,
      newHref: canCreateWorkshops(user) ? "/workshops?new=1" : undefined,
      updateHref: "/workshops",
      visible: canManageWorkshops(user),
      group: "records"
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
      {/*
        THE MINISTRY DESK — drawn for the three directorate posts and the master admin, and for
        nobody else. It renders null for everyone outside that set, so every other account's
        dashboard is byte-identical to what it was before this line existed.

        ABOVE THE GRID, AND NOT IN IT. None of the five destinations it gathers is an Android
        `EntryMode`, so none of them can join the `tiles` array below without putting the web out of
        step with the handset — two parity tests, one per client, read that array. A sibling section
        costs the grid nothing and is checked by neither, which is the correct answer for a web-only
        card serving four tiers.

        AND ABOVE RATHER THAN BELOW, because below is roughly nineteen tiles of a designer's work
        away. "Gather their screens so they are easy to reach" is not answered by putting them after
        everything that is not theirs.

        THE COMPONENT OWNS ITS OWN GATE — `canSeeMinistryDesk`, plus each row's own destination
        predicate — rather than being wrapped in a condition here. One gate, in the file whose whole
        subject it is, is one place to read it and one place to change it; a second copy in this
        page's JSX would be the hand-kept mirror this repository keeps paying for.
      */}
      <MinistryDeskCard />
      {/* The tiles are glass, and glass on a flat canvas refracts nothing you can see — these
          two soft orbs are what their rims bend. Purple only: `grad-mesh` carries a faint amber
          orb, and gold belongs to the marketing surfaces, never to a data screen. */}
      <div className="relative">
        <div aria-hidden className="pointer-events-none absolute -inset-x-6 -inset-y-8 overflow-hidden">
          <div className="absolute -left-12 -top-4 h-72 w-72 rounded-full bg-purple-300/25 blur-3xl" />
          <div className="absolute -right-8 bottom-0 h-80 w-80 rounded-full bg-purple-400/20 blur-3xl" />
        </div>
        {/*
          ── THE FOUR MEGA CARDS, AND THE ONE EXPRESSION EVERYTHING STILL FLOWS THROUGH ─────────

          `.filter((tile) => tile.visible !== false)` IS THE FIRST STEP AND STAYS SPELLED EXACTLY SO.
          `dashboard-tile-parity-unit.spec.ts` asserts that literal substring, character for
          character including the parameter name — because the filter is default-ALLOW, and a tile
          whose `visible` key was deleted is shown to every signed-in account. Grouping happens
          AFTER it, so a tile this account may not have cannot reappear inside a mega card.

          THE GROUPS ARE DERIVED FROM `TILE_GROUPS` AND NOT WRITTEN OUT AS FOUR BLOCKS OF JSX. A
          hand-written block per group is a fifth register of the same fact, and this file already
          carries the cost of that lesson at length — the tiles array was itself the register nobody
          enumerated. An unrecognised or absent group falls into Miscellaneous rather than vanishing:
          nothing this filter admitted may fail to be drawn.

          AN EMPTY GROUP RENDERS NOTHING AT ALL — no heading, no empty card. A researcher has no
          Admin tiles and a volunteer has no Records tiles, and a heading over nothing reads as a
          section that failed to load. `DynamicIslandNav` makes the same choice for an empty nav
          group, for the same reason.
        */}
        <div className="relative grid gap-6">
          {(() => {
            const visible = tiles.filter((tile) => tile.visible !== false);
            const seen = new Set<Tile>();
            return TILE_GROUPS.map((group, index) => {
              const members = visible.filter((tile) => {
                const belongs =
                  tile.group === group.id ||
                  // The catch-all, and it is the LAST group's job rather than a default on the type,
                  // so it cannot silently claim a tile that a real group also matched.
                  (group.id === "misc" && !TILE_GROUPS.some((candidate) => candidate.id === tile.group));
                if (belongs) seen.add(tile);
                return belongs;
              });
              if (members.length === 0) return null;
              return (
                <section key={group.id} aria-labelledby={`dashboard-group-${group.id}`}>
                  <div className="mb-3 flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
                    <h2
                      id={`dashboard-group-${group.id}`}
                      className="font-display text-lg font-bold text-ink-900"
                    >
                      {group.title}
                    </h2>
                    <p className="text-xs text-ink-500">{group.note}</p>
                  </div>
                  {/* The grid geometry is unchanged — two per row on phones, three on tablets and
                      laptops — because it is Android's (`grid-cols-2 md:grid-cols-3`) and the
                      handset's dashboard is the same product. Only the rows are now grouped. */}
                  <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
                    {members.map((tile) => (
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
                  {/*
                    ── THE DESTINATIONS THAT BELONG IN THIS GROUP AND CANNOT BE TILES ─────────────

                    `/questionnaires` — "My questionnaires", the designer's own .xlsx-derived
                    instrument and its pro-forma — is already open to a DESIGNER: `ROUTE_GUARDS`, the
                    nav entry and every route in `questionnaire_forms.py` all resolve to
                    `canRunDesignWorkshops`, and DESIGNER is the first member of that set. What a
                    designer did NOT have was a way to FIND it. It was reachable only from the nav
                    sheet — behind a tap, in one scrolling column — which is the exact failure
                    `e2e/feature-entry-points.spec.ts` opens by naming: a feature a user cannot find
                    is a feature that was not built. The owner asked for "access"; access was already
                    there, and this is the half that was missing.

                    ⚠ IT IS NOT A TILE, AND IT MAY NOT BECOME ONE.
                    `android/.../DashboardTileParityTest.kt` asserts `WEB_ONLY == emptyList()` in BOTH
                    directions — every web tile label must also be an Android card label — and the
                    handset has no `EntryMode` for this destination. A tile here would go red on
                    `main` rather than on the PR that added it, because that suite is not in the
                    frontend gate. `dashboard-tile-parity-unit.spec.ts`'s closed FAMILY literal says
                    the same thing from this side: `"/questionnaires": false`, with its own note that
                    the list is what must change if the owner ever wants it on the grid.

                    A SIBLING ROW INSIDE THE GROUP IS THE ESTABLISHED ANSWER — the shape
                    `MinistryDeskCard` uses on this same page for the same reason, and it costs the
                    parity-checked grid nothing.
                  */}
                  {group.id === "designers" ? (
                    <Link
                      href="/questionnaires"
                      className="mt-3 flex items-start gap-3 rounded-md border border-line-200 bg-card p-3 transition-shadow hover:border-purple-300 hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-purple-700"
                    >
                      <span aria-hidden className="grid h-9 w-9 shrink-0 place-items-center rounded-md bg-purple-50 text-purple-700">
                        <FileSpreadsheet className="h-[18px] w-[18px]" aria-hidden />
                      </span>
                      <span className="min-w-0 flex-1">
                        {/* The nav's label, character for character. This destination already answers
                            to one name in the menu and a second invented here is a name nobody's grep
                            finds and nobody's colleague recognises. */}
                        <span className="block font-display text-sm font-bold text-ink-900">My questionnaires</span>
                        <span className="mt-0.5 block text-xs leading-5 text-ink-500">
                          Build your own interview form from the .xlsx pro-forma, and record answers
                          against it — separate from the shared artisan questionnaire on Take interview.
                        </span>
                      </span>
                    </Link>
                  ) : null}
                  {index === TILE_GROUPS.length - 1 && seen.size !== visible.length ? (
                    /* UNREACHABLE, AND WRITTEN ANYWAY. Every tile the filter admits is claimed by a
                       real group or by Miscellaneous's catch-all, so this cannot fire today. The day
                       somebody adds a fifth group id without adding it to `TILE_GROUPS`, the
                       alternative to this line is a tile that is simply not on the dashboard — which
                       is exactly the defect `dashboard-tile-parity-unit.spec.ts` exists for, arriving
                       through the one door that spec does not watch. */
                    <p className="mt-3 text-xs leading-5 text-ink-500">
                      {visible.length - seen.size} of your entries could not be filed under a heading
                      and are not shown above. This is a fault in this screen, not a change to what
                      you may open — every one of them is still in the navigation menu.
                    </p>
                  ) : null}
                </section>
              );
            });
          })()}
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
