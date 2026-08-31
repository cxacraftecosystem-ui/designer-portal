import type { User, UserRole } from "@/lib/types";

/**
 * The EIGHT-tier role ladder, mirroring the backend exactly (app/core/deps.py).
 * Higher rank inherits every power of the ranks below it; the grantable can*
 * booleans additionally lift a single capability for a lower tier.
 *
 * EIGHT SINCE 2026-08-27, when INSPECTOR (37) was inserted between DESIGNER and PROFESSOR. The
 * count in this sentence is now the ONLY hand-kept count left in this client: `AccessLadder.tsx`
 * derives its heading from `ROLES_BY_RANK.length`, and `backend/tests/test_role_ladder_parity.py`
 * holds the MAPS below to the server. Nothing counts this paragraph.
 *
 * IT SAID SIX FOR AS LONG AS DESIGNER HAS EXISTED — the same off-by-one the
 * backend's own ladder carried, corrected there with a note saying so. The tier is in the map
 * below, with its own explanation of why 35. It is not a typo with no consequence: this file is
 * where every client-side permission question is answered, and a reader who trusts prose over map
 * goes looking for six rows in a product whose primary user is not among them. README.md's role table
 * and docs/PERMISSIONS.md had already been corrected for the same miscount, and this map is the
 * client's answer to every permission question, so it is the worst remaining place to be wrong.
 * IT IS NOT THE LAST ONE, and the remaining list is now short enough to name rather than gesture at.
 * Nothing in the web client miscounts any more: the one site that was RENDERED CONTENT rather than a
 * comment — `components/hero/AccessLadder.tsx`, the public landing page's ladder, a literal six-row
 * array with no Designer in it whose own header claimed "the exact labels of ROLE_LABELS in
 * lib/permissions.ts" — now derives its rows, its labels and the count in its own heading from
 * `ROLES_BY_RANK` / `ROLE_LABELS` here, over a `Record<UserRole, string>` of copy that fails `tsc`
 * until a new tier is given its sentence. A SECOND rendered one hid from that sweep by saying SEVEN
 * rather than six: `app/login/page.tsx`'s `BRAND_POINTS` shipped "Seven-tier access control" past
 * INSPECTOR while the hero badge beside it, which speaks the same sentence from `TIER_COUNT_WORD`,
 * re-counted itself. It now reads Eight and carries a note saying it is hand-kept and why. What is
 * left is outside this client: `docs/PERMISSIONS.md`
 * and `SESSION_HANDOVER.md` say "six-tier" only to narrate that correction, `docs/RESEARCH_NOTES.md`
 * keeps a provenance-labelled six-row snapshot on purpose and says so, and the live miscount is
 * now ZERO. The last one standing was `MainActivity.kt` in the Android client, which said
 * "six-tier ladder" twice — once in RENDERED SCREEN COPY on the users-and-access card
 * ("Professors and above can move a user along the six-tier ladder"), which was the worst of the
 * lot because a user read it, and once in the comment above the role dropdown on that same card,
 * whose options are built from `ROLE_RANK` and so already listed eight. Both were corrected to
 * "eight-tier" on 2026-08-27, the comment carrying the count's source with it.
 * `ui/AppNavigation.kt` ("The EIGHT-tier ladder"),
 * `backend/.env.example` ("eight tiers as of 2026-08-27") and the frontend skill file agents load,
 * `.claude/skills/field-repo-frontend/SKILL.md` ("**Eight**-tier ladder"), were all on this list
 * and are off it — each was corrected in the INSPECTOR wave. `backend/app/core/config.py` was on it
 * and should not have been: its "pre-six-tier behavior" dates an ERA, not the present ladder.
 * Counted 2026-08-27 by grepping `six-tier|seven-tier|six tiers|seven tiers` over the tree, and
 * RE-COUNTED the same day with `git grep` after the Android correction landed: every surviving
 * hit is a sentence narrating one of these corrections, the deliberate `RESEARCH_NOTES.md`
 * snapshot, or the `20260724120000_six_tier_roles` migration folder, which is a name and is history.
 * `backend/tests/test_review_edit_authority.py` was on that list and is off it, because it was not a
 * stale comment at all: its `ALL_ROLES` had actually LOST DESIGNER, so the review-edit matrix ran 36
 * pairs instead of 49 and never asked a single question about the tier. It now DERIVES the tuple
 * from `deps.ROLE_RANK`, which is the only fix that stays fixed.
 * Nothing mechanical counts prose, which is why they rot one file at a time — cited by string and
 * not by line, because these files move under each other.
 *
 * "MIRRORING EXACTLY" IS A CLAIM ABOUT FOUR PROPERTIES — the same eight keys, at the same numbers,
 * with the same labels, in the same declaration order — and it is worth saying which of them a
 * machine checks, because "already right" and "asserted" are different states:
 *  - KEYS and NUMBERS: `docs/tools/check-docs.mjs::checkRoleParity` parses `ROLE_RANK` out of both
 *    this file and `backend/app/core/deps.py` and diffs them in both directions.
 *  - LABELS and KEY ORDER: `frontend/e2e/role-ladder-parity-unit.spec.ts`, which reads `deps.py`
 *    off disk and diffs `ROLE_LABELS` and the key sequence the same way. Before that spec existed
 *    this paragraph claimed all four had been "compared when the note was written", which is a
 *    hand-check dressed as a guarantee.
 * `ROLE_LABELS` has FIVE copies in this repository — here, `deps.py`, and THREE in the Android
 * client (`MainActivity.kt`, `TaskAdminScreen.kt`, and `ui/AppNavigation.kt`'s `LABELS`, which this
 * paragraph missed while telling other files off for undercounting). All five are now diffed
 * against `deps.py` by `backend/tests/test_role_ladder_parity.py`, which is what stopped the Kotlin
 * trio being "hand-kept, correct when last read, and nothing would say if it stopped being".
 *
 * DECLARATION ORDER IS A CONVENTION HERE, NOT A BEHAVIOUR, and an earlier draft of this note said
 * the opposite. `ROLES_BY_RANK` below sorts on the VALUES, and all eight ranks are distinct, so the
 * array it produces is identical whatever order these keys are written in — nothing in the client
 * reads the declaration order at all (`ROLES_BY_RANK` and `ROLE_RANK` are read only by
 * `AssignmentBuilder.tsx` and `activity/page.tsx`, both by value). The order is kept in step with
 * `deps.py` so the two files diff against each other by eye, and the spec pins it for that reason
 * alone. A picker's order comes from the sort, and the sort cannot drift.
 */
export const ROLE_RANK: Record<UserRole, number> = {
  CROWDSOURCE_VOLUNTEER: 10,
  FIELD_CONTRIBUTOR: 20,
  RESEARCHER: 30,
  // 35, in the gap the original tens left. Mirrors ROLE_RANK in backend/app/core/deps.py; the
  // two must agree or the UI offers actions the API refuses.
  DESIGNER: 35,
  // 37 — inspects and reviews a designer's work without running workshops. Added 2026-08-27.
  //
  // WHY 37: it is the MIDDLE of the free 36-39 band between DESIGNER and PROFESSOR, so a gap stays
  // open on both sides for a future insert. Renumbering the tiers around it instead would change
  // the meaning of every comparison in this file at once.
  //
  // WHAT IT MEANS FOR THE UI. This client's design-workshop controls are gated on SETS
  // (`canRunDesignWorkshops`, `canCreateDesignWorkshops`), not on this number, so an inspector is
  // offered no workshop control by the rank alone — correct, and the same position PROFESSOR is in.
  // What the rank DOES change is `canReview`-adjacent chrome: at 37 an inspector outranks a
  // designer, so the review queue will offer them a designer's records. That is the point of the
  // tier; `backend/app/core/deps.py::can_review_record` is the gate that actually decides it and
  // `backend/tests/test_inspector_tier.py` pins the answer. Hiding a control is not the rule.
  INSPECTOR: 37,
  PROFESSOR: 40,
  ADMIN: 50,
  MASTER_ADMIN: 60
};

export const ROLE_LABELS: Record<UserRole, string> = {
  CROWDSOURCE_VOLUNTEER: "Crowdsource Volunteer",
  FIELD_CONTRIBUTOR: "Field Contributor",
  RESEARCHER: "Researcher",
  DESIGNER: "Designer",
  // BOTH WORDS. The stored token is INSPECTOR because "review" already names the relational sense
  // (`canReview` = "may act on anyone below me"); the label says "Reviewer" too because that is the
  // word a user searching a role picker for themselves will type. Byte for byte the server's
  // ROLE_LABELS["INSPECTOR"] — `frontend/e2e/role-ladder-parity-unit.spec.ts` diffs the spelling.
  INSPECTOR: "Inspector / Reviewer",
  PROFESSOR: "Professor",
  ADMIN: "Admin",
  MASTER_ADMIN: "Master Admin"
};

/** All roles, highest tier first — the display order for pickers. */
export const ROLES_BY_RANK: UserRole[] = (Object.keys(ROLE_RANK) as UserRole[]).sort(
  (a, b) => ROLE_RANK[b] - ROLE_RANK[a]
);

export function roleRank(userOrRole: User | UserRole | null | undefined): number {
  if (!userOrRole) return 0;
  const role = typeof userOrRole === "string" ? userOrRole : userOrRole.role;
  return ROLE_RANK[role] ?? 0;
}

export function roleLabel(role: string | null | undefined): string {
  return ROLE_LABELS[role as UserRole] ?? String(role ?? "");
}

export function hasRank(user: User | null | undefined, role: UserRole): boolean {
  return roleRank(user) >= ROLE_RANK[role];
}

/** Roles the current user may assign: at or below their own tier (master mints anything). */
export function assignableRoles(user: User | null | undefined): UserRole[] {
  return ROLES_BY_RANK.filter((role) => ROLE_RANK[role] <= roleRank(user));
}

/**
 * May the current user manage (promote/demote, and for admins edit/delete) this target?
 * Master admin manages anyone except OTHER master-admin rows; everyone else manages only
 * users ranked strictly below them.
 */
export function canManageUser(user: User | null | undefined, target: User): boolean {
  if (isMasterAdmin(user)) return target.role !== "MASTER_ADMIN" || target.id === user?.id;
  return roleRank(target) < roleRank(user);
}

/** Professors and above run the user table (promotion rights; admins add create/delete/grants). */
export function canManageUsers(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR");
}

/** Only admins and the master admin may assign tasks to other users. */
export function canAssignTasks(user: User | null | undefined) {
  return hasRank(user, "ADMIN");
}

export function isAdmin(user: User | null | undefined) {
  return user?.role === "MASTER_ADMIN" || user?.role === "ADMIN";
}

export function isMasterAdmin(user: User | null | undefined) {
  return user?.role === "MASTER_ADMIN";
}

export function canManageQuestionnaire(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR") || !!user?.canManageQuestionnaire;
}

/**
 * Add or edit a craft — `can_manage_crafts` / `require_craft_manager`. Professor and above, RANK
 * ALONE: the `canManageCrafts` column is no longer read on either side, because a per-user grant
 * that lifted a researcher over the taxonomy was invisible in the role column. Deleting a craft is
 * stricter still (admin), so a delete control needs `isAdmin`, not this.
 */
export function canManageCrafts(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR");
}

/** Add or edit a workshop — `require_workshop_manager`. Professor+; deleting one is admin-only. */
export function canManageWorkshops(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR");
}

/** Anyone with somebody ranked below them may peer-review (plus grantees of canReview). */
export function canReview(user: User | null | undefined) {
  return hasRank(user, "FIELD_CONTRIBUTOR") || !!user?.canReview;
}

export function canDownloadDataset(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR") || !!user?.canDownloadDataset;
}

/**
 * Who the dashboard offers the "View Data" tile to — Researcher, Professor, Admin and Master Admin,
 * and nobody else.
 *
 * ── A FLOOR PLUS ONE CARVE-OUT, WHICH IS THE OWNER'S OWN INSTRUCTION ─────────────────────────────
 *
 * The owner's instruction is "view data card should not be there for designers; it is only for
 * admins, master admins, professors, and researchers", and then, on the mechanism: "do it through
 * floor for admin, master admin, and professor, but researchers should have access to the view data
 * as well, implement a mechanism for the same".
 *
 * Those four tiers are RESEARCHER(30), PROFESSOR(40), ADMIN(50) and MASTER_ADMIN(60). NO PURE FLOOR
 * EXPRESSES THAT SET: the tightest one that admits RESEARCHER also admits DESIGNER(35) and
 * INSPECTOR(37), the two tiers sitting inside the range, and every threshold instinct gets them
 * wrong. So the rule is a floor at PROFESSOR — which covers three of the four by rank — plus
 * RESEARCHER named once, explicitly. `canDownloadDataset` directly above is the same shape (a floor
 * plus a per-user carve-out), so this is the file's own idiom rather than a new one.
 *
 * ── WHY NOT A FOUR-ITEM ARRAY, WHICH IS WHAT THIS WAS FIRST WRITTEN AS ───────────────────────────
 *
 * Because a hand-written role list silently EXCLUDES a tier added above professor, and this ladder
 * grows. INSPECTOR landed on 2026-08-27 and twenty-two hand-kept copies of the ladder had to be
 * found and corrected across both clients, the tests and the docs;
 * `backend/tests/test_role_ladder_parity.py` exists precisely because those lists rot one file at a
 * time. A floor picks up a new senior tier by construction; an array waits for somebody to remember.
 * The carve-out is the one part that must be spelled out, and it is one token long.
 *
 * DO NOT "SIMPLIFY" THIS TO `hasRank(user, "RESEARCHER")`. That is the single edit this predicate
 * exists to survive, and it hands the tile to designers and inspectors — the exact two tiers the
 * instruction excludes. The unit spec demonstrates that independently rather than asserting it.
 *
 * ── WHAT IT DECIDES, WHICH IS ONE TILE ───────────────────────────────────────────────────────────
 *
 * This is NOT an entitlement and it is NOT a route guard, and calling it one would be the lie the
 * comment on `ROUTE_GUARDS` warns about:
 *
 *   * `/data` keeps its own row above, gated on `canDownloadDataset` — Professor and above, or the
 *     explicit per-user grant. Nothing here widens or narrows that.
 *   * `/search` ("Browse records") stays open to every signed-in account, exactly as its `NAV_ITEMS`
 *     entry says (`gate: "get_current_user"`), because the endpoints behind it take nothing more
 *     than a signed-in user and scope their rows per viewer on the server. A `ROUTE_GUARDS` row
 *     there would be a client-side rule the API does not have.
 *
 * So a designer keeps every record they could read before; what they lose is a tile on the screen
 * the app opens on, which was pointing most tiers at a destination the menu had already decided they
 * were not the audience for. The tile and the "View Data" menu row disagreed outright before this:
 * the row is `canDownloadDataset`, the tile was shown to everybody.
 *
 * ── THE TWO TIERS THE INSTRUCTION DID NOT NAME ───────────────────────────────────────────────────
 *
 * CROWDSOURCE_VOLUNTEER(10) and FIELD_CONTRIBUTOR(20) are OUT, deliberately. The instruction is a
 * whitelist of four and both sit below every one of them; and neither loses a way in, because
 * "Browse records" is an ungated menu row to the same `/search` the tile was sending them to.
 *
 * ── AND THE `canDownloadDataset` GRANT IS DELIBERATELY NOT AN ESCAPE HATCH HERE ───────────────────
 *
 * A DESIGNER holding the explicit grant still sees the "View Data" MENU ROW and still opens `/data`
 * — nothing is taken from them — but gets no tile, because the tile answers "is this account part of
 * this destination's audience", which is a question about the tier. Admitting a per-user boolean
 * would make one dashboard tile's presence invisible in the role column, which is precisely the
 * argument {@link canManageCrafts} gives for being rank-only.
 */
export function canSeeDataTile(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR") || user?.role === "RESEARCHER";
}

/**
 * Opening a NEW artisan, product, tool, process or interview — `require_record_creator`. Researcher
 * and above. The two tiers below populate records instead of opening them, and none of what they do
 * is gated by this: uploading media, answering an existing interview and commenting all stay open,
 * so hiding a "New …" control from them never hides a contribution path.
 */
export function canCreateRecords(user: User | null | undefined) {
  return hasRank(user, "RESEARCHER");
}

export function canEditOwnOrAdmin(user: User | null | undefined, ownerId?: string | null) {
  return isAdmin(user) || (!!user?.id && !!ownerId && user.id === ownerId);
}

/**
 * Provenance — created-by plus the per-field edit history. Android parity
 * (MainActivity `canViewProvenance = isAdmin || user.canViewProvenance`): admins always, plus
 * anyone the master admin granted the capability. Admin view may hide it from an admin, but a
 * grantee keeps it permanently.
 */
export function canViewProvenance(user: User | null | undefined) {
  return isAdmin(user) || !!user?.canViewProvenance;
}

/**
 * Managed API keys (GET/PUT/DELETE /secrets, /secrets/{key}/reveal, /secrets/{key}/test) and the
 * repository's global app settings. Master admin ONLY — an ordinary admin never sees a key value.
 */
export function canManageSecrets(user: User | null | undefined) {
  return isMasterAdmin(user);
}

/* ────────────────────────────────────────────────────────────────────────────
 * Route guards — the page-level half of gating.
 *
 * A hidden nav entry is not a guard. /users, /review, /data and the create forms are all reachable
 * by typing the URL, so every wholly-gated route is declared ONCE here and AppShell enforces the
 * list for the entire (protected) tree. A new page therefore cannot ship without its guard being a
 * deliberate decision, and a page that also guards itself is simply defended twice.
 *
 * Matching is by path segment: a rule for "/users" also covers "/users/anything", and the LONGEST
 * matching rule wins, so "/artisans/new" can be stricter than "/artisans". Anything unlisted is
 * open to any signed-in user — that is the correct default for the read surfaces (lists, search,
 * activity, tasks, sharing, feedback, the walkthrough and a user's own settings).
 *
 * Admin view is deliberately NOT consulted. The toggle hides admin chrome from an admin who wants
 * to browse as an ordinary user; it is not a permission, and it must never lock an admin out of a
 * URL the API would happily serve.
 * ──────────────────────────────────────────────────────────────────────────── */

export type RouteGuard = {
  /** The path this rule covers, together with everything nested beneath it. */
  path: string;
  can: (user: User | null | undefined) => boolean;
  /** The backend dependency this mirrors (backend/app/core/deps.py) — keep the two in step. */
  gate: string;
  title: string;
  message: string;
};

/** Shared copy for the four create routes, mirroring `require_record_creator`'s 403 detail. */
const RECORD_CREATOR_GUARD = {
  can: canCreateRecords,
  gate: "require_record_creator",
  title: "Researcher access required",
  message:
    "Creating artisans, products, processes and tools needs Researcher access or above. " +
    "Field contributors and crowdsource volunteers answer existing interviews, upload media, and " +
    "comment on existing records — browse the repository to find an entry to add to."
} as const;

export const ROUTE_GUARDS: RouteGuard[] = [
  {
    path: "/users",
    can: canManageUsers,
    gate: "require_professor",
    title: "Professor access required",
    message:
      "Managing users — roles, capability grants, and account creation — is available to professors, admins and the master admin."
  },
  {
    path: "/admin",
    can: isAdmin,
    gate: "require_admin",
    title: "Admin access required",
    message: "The settings hub is available to admins and the master admin only."
  },
  {
    /*
      Cross-workshop analytics. Nested under /admin, so the hub's rule would already refuse
      everyone below admin — this row is here to name the RIGHT refusal, and to be the thing that
      changes if the server's gate ever moves.

      A DESIGNER IS REFUSED HERE, and it is the one refusal on this page worth spelling out,
      because a designer runs the workshops this reads. They see their own workshops and the ones
      an admin has added them to (`DesignWorkshopViewer`); this aggregates every cluster in the
      scheme, including the workshops they were deliberately not given, so it is a strictly wider
      visibility than any per-record grant confers. The server says the same thing in one line —
      `Depends(require_admin)` on GET /api/analytics/design-workshops — and that is the boundary.
      This rule only stops the browser rendering a page the API would refuse; the URL was open to
      anyone with the link before it existed, which is the bug this repo has shipped twice.
    */
    path: "/admin/analytics",
    can: isAdmin,
    gate: "require_admin",
    title: "Admin access required",
    message:
      "Comparing adoption, costs and outcomes ACROSS workshops aggregates fieldwork from clusters and designers beyond your own, so it is available to admins and the master admin. Your own workshops, with the same stage 22 follow-up records, are on Design workshops."
  },
  {
    // Nested under /admin, which already refuses everyone below admin — so this rule changes no
    // decision today and is not redundant either. `require_designer_roster_manager` is a predicate
    // of its own on the server, and the day it moves (a "roster manager" grant, say) the two would
    // silently disagree if this route were still riding on the hub's `require_admin`. The longest
    // matching rule wins, so this one answers, and its copy names the ROSTER rather than the
    // settings hub the /admin rule would otherwise talk about.
    path: "/admin/designers",
    can: canManageDesignerRoster,
    gate: "require_designer_roster_manager",
    title: "Admin access required",
    message:
      "The designer roster decides who may sign in as a designer at all, and it is a list of named individuals and their institutional standing — so reading it is admin work as much as writing it is. Admins and the master admin add, suspend and restore designers there."
  },
  {
    // The PLATFORM allow-list, and the queue of people waiting to be let in. Nested under /admin
    // like the roster above and here for the same reason: `require_access_manager` is a predicate of
    // its own on the server, and the day it moves the two would silently disagree if this route were
    // still riding on the hub's `require_admin`.
    //
    // The copy names the QUEUE and not only the list, because an account below admin most often
    // arrives here having been sent the link by a colleague who cannot sign in — and a refusal that
    // said only "you cannot read the list" would leave them believing that colleague's request is
    // nowhere at all.
    path: "/admin/access",
    can: canManageAccessRoster,
    gate: "require_access_manager",
    title: "Admin access required",
    message:
      "Who may sign in to this application at all — and the queue of people waiting for a decision — is settled by admins and the master admin. The queue is a list of named people who tried to get in, so reading it is restricted for the same reason deciding it is."
  },
  {
    // The page now holds two things with two different owners, so the ROUTE is admin and the halves
    // gate themselves. Key VALUES stay master-admin (every /secrets route is require_master_admin,
    // and the page renders ApiKeysPanel only for them); RANKING the transcription providers is
    // require_admin on the server, and this guard used to slam the door on the admins entitled to
    // it — the ranking was unreachable for the exact people who asked for it.
    path: "/settings/api-keys",
    can: isAdmin,
    gate: "require_admin",
    title: "Admin access required",
    message:
      "Provider keys and the transcription provider order are managed by admins and the master admin. Reading or replacing a key value is the master admin's alone."
  },
  {
    // Usage aggregates navigation across every account on the platform — which screens, how often,
    // how fast, how often broken — so it is admin work for the same reason cross-workshop analytics
    // is: it is a strictly wider view than any one account's own activity. `require_usage_reader` is
    // a predicate of its own on the server (`deps.can_read_usage`, Admin and above, deliberately not
    // Researcher — see that function's docstring for why the research use case does not lower it),
    // so this rule changes no decision today but keeps the two from silently disagreeing the day
    // `require_admin` and `require_usage_reader` diverge. No `/usage/me` route is linked from
    // anywhere below this page — an account's own trail is not exposed as a UI at all yet, so there
    // is no ordinary-user alternative to send a refused visitor to.
    path: "/settings/usage",
    can: isAdmin,
    gate: "require_usage_reader",
    title: "Admin access required",
    message:
      "Usage is an aggregate view across every account's navigation on the platform, so it is available to admins and the master admin."
  },
  {
    // Batch task assignment (POST /tasks/batch, /tasks/batches, /tasks/progress) is admin-only;
    // /tasks itself stays open, because every user can be an assignee.
    path: "/settings/tasks",
    can: canAssignTasks,
    gate: "require_admin",
    title: "Admin access required",
    message:
      "Assigning documentation tasks and tracking their progress is available to admins and the master admin. Your own assigned tasks are on the Tasks page."
  },
  {
    path: "/review",
    can: canReview,
    gate: "require_reviewer",
    title: "Review access required",
    message:
      "The review queue opens for Field Contributors and above — everyone with someone ranked below them — plus anyone granted review access."
  },
  {
    path: "/data",
    can: canDownloadDataset,
    gate: "require_dataset_downloader",
    title: "Dataset access required",
    message:
      "Browsing and downloading the full dataset is available to professors and above, or to anyone granted dataset-download access. Browse records to search the repository instead."
  },
  {
    // Hiding the nav entry was never enough: the link disappeared and the URL stayed open, so
    // anybody who had been sent one, or who had it in their history, walked straight in. The
    // server refuses every write (`_require_designer` on the routes), but the LIST page still
    // rendered its chrome to somebody who could do nothing with it.
    //
    // THIS IS `canRunDesignWorkshops` AND NOT `canCreateDesignWorkshops`, and the difference is the
    // entire point of the two predicates. A DESIGNER may not START a workshop any more, and may
    // absolutely still open this page: it is where the workshops they have been given access to
    // are listed, and where their fortnight of unsent fieldwork lives. Narrowing this row to the
    // create set would lock a designer out of their own work to enforce a rule about a button.
    // Creating is a CONTROL, not a route — there is no `/design-workshops/new` — so it is gated
    // where it is rendered (the page) and where it is performed (`lib/designWorkshopStore.ts` for
    // the offline path, `POST /design-workshops` for the online one), not here.
    /*
      THE PROVENANCE VIEW IS ADMIN, AND IT OUTRANKS `/design-workshops` BY BEING LONGER.

      `routeGuardFor` keeps the LONGEST matching rule rather than the first, so position in this
      array is irrelevant and this row wins over the `/design-workshops` prefix on its own path
      regardless of where it sits. (An earlier draft of this comment claimed the opposite and put
      the row here to exploit it; both halves were wrong, and a rule that depends on array order
      would be a live hazard the day somebody sorted this table.)

      It uses a `:id` segment, which `routeMatches` did not understand until this row needed it —
      see that function.

      Why admin and not the workshop's own designers: this view crosses OUT of the workshop into the
      shared record tables and reports one account's data beside another's, which is the line
      `isAdmin` draws everywhere else. The stage reads are unaffected — every designer still sees the
      per-field stamps under their own boxes; what they do not see is the canonical comparison.
    */
    path: "/design-workshops/:id/provenance",
    can: isAdmin,
    gate: "require_admin (GET /design-workshops/{id}/provenance)",
    title: "Admin access required",
    message:
      "Field-by-field provenance across the shared records reports one account's data beside another's, so it is an admin view. The per-field authorship on each stage is unaffected and stays open to every designer on the workshop."
  },
  {
    /*
      THE POOL REVIEW ROUND. A rule this table's own closing note predicted, in the exact words:
      "a maintainer adding a page beside the design-workshop tree read this table, found nothing,
      believed the closing sentence and shipped without a guard entry." That is what happened — the
      page went out on 2026-08-22 with a header comment naming this missing row as a debt it could
      not pay, because this file belonged to another unit that hour. Paid 2026-08-23.

      IT DOES NOT RIDE ON `/design-workshops`. The route is `/design-review`, a sibling and not a
      child, because the pool round deliberately reaches ACROSS workshops — a designer ranks work
      from rounds they were never added to, which is the whole difference between the second review
      level and the first. So no prefix rule covers it and, unlike `/admin/analytics` or
      `/admin/designers`, there was no wider rule quietly refusing the wrong people in the meantime:
      until this row existed the URL was open to every signed-in account.

      Same SET as the workshop tree — Designer, Admin, Master Admin — so a **professor is refused**,
      and that is not derivable from the rank ladder in §2. The server says it in
      `load_ratable_workshop_or_404`, whose first line refuses anybody outside
      `can_run_design_workshops`; the page repeats it in its own words for the case where a round id
      is already in the URL. This rule is the first line, and the reason the other two are a second
      and third: a page that defends itself still renders its shell first, and the shell of a review
      round names the workshops in it.
    */
    path: "/design-review",
    can: canRunDesignWorkshops,
    gate: "can_run_design_workshops (load_ratable_workshop_or_404)",
    title: "Designer access required",
    message:
      "A review round ranks named designers' sketches and prototypes against each other and records who said what, so it is read and rated by designers, admins and the master admin."
  },
  {
    /*
      SKETCHES & PROTOTYPES, CHOSEN-WORKSHOP-FIRST. The same screen as the per-workshop page at
      /design-workshops/[id]/sketches-and-prototypes, entered from the other end. There, the
      workshop is already in the URL because the designer walked into it through the workshop's own
      hub; here, the designer arrives from the menu with nothing chosen and picks the workshop on
      the page. One extracted component renders both, so the two cannot drift in WHAT they show —
      but they do not share a guard, and that asymmetry is the only reason this row has to exist.

      WHY IT IS A SIBLING OF THE WORKSHOP TREE AND NOT A CHILD. A route can only sit beneath
      /design-workshops/:id if an id is known before the page renders, and being reachable when it
      is NOT known is this page's entire purpose. There is no id to put in the path, so the path
      cannot be nested — and the moment it is not nested, the `/design-workshops` rule immediately
      below stops covering it. `routeMatches` compares whole segments: "/sketches-and-prototypes"
      is neither equal to "/design-workshops" nor prefixed by "/design-workshops/", so before this
      row nothing in this table answered for it at all. The per-workshop twin, meanwhile, needs no
      row of its own — that prefix rule covers it, as it covers every other page inside a workshop.
      Two URLs, one component, one of them gated by a prefix and the other needing its own entry: a
      reader cannot re-derive that from either file, which is why it is written down rather than
      left to be noticed.

      WITHOUT THIS ROW THE URL IS OPEN TO EVERY SIGNED-IN ACCOUNT. Not refused-by-a-wider-rule the
      way `/admin/analytics` and `/admin/designers` are, and not merely unadvertised: `AppShell`
      applies whatever `routeGuardFor` returns and nothing else, so no row means no refusal, and the
      nav entry in `components/DynamicIslandNav.tsx` withholds only the LINK. That is the bug this
      file has now recorded three times — `/design-workshops` itself, then `/design-review` on
      2026-08-22, now this page — and each time the page shipped before the row did, by a maintainer
      who had read the table, found nothing beside the design-workshop tree, and believed the
      closing sentence of docs/PERMISSIONS.md §5.

      Same SET as `/design-workshops` and `/design-review` — Designer, Admin, Master Admin — so a
      PROFESSOR IS REFUSED, which the rank ladder in docs/PERMISSIONS.md §2 will not give you: a
      professor outranks a designer everywhere else in this file.

      ON THE PICKER THIS IS A NARROWING AND NOT A MIRROR, and the honest `gate` below says so.
      `list_design_workshops` takes `get_current_user` — there is no role dependency on the list at
      all — and scopes rows with `visible_to_clause`, so the server would answer a professor with an
      empty list rather than a refusal. The refusals are one layer in: the chosen workshop's stage
      rows go through `load_workshop_or_404`, and the pool round through
      `load_ratable_workshop_or_404`, which tests `can_run_design_workshops(user)` — NOT as its first
      line, and the correction matters only to a reader reasoning about it: the role test is third,
      after the `find_unique` and the `deletedAt` check. Nothing observable turns on the order,
      because all three raise the identical 404 with the identical detail; what does turn on it is
      any future argument that the existence check cannot precede the role check. It does.
      So this rule is the first of three lines and the page refuses for itself as the second, for
      the same reason `/design-review` does — a page that defends itself still renders its shell
      first, and this shell is a list of workshop names.
    */
    path: "/sketches-and-prototypes",
    can: canRunDesignWorkshops,
    gate: "can_run_design_workshops (load_workshop_or_404 once a workshop is chosen; the picker's list is get_current_user + visible_to_clause)",
    title: "Designer access required",
    message:
      "Sketches and prototypes are a named designer's work in progress, uploaded to a workshop and then ranked against other designers' pieces under the name of whoever ranked them, so this page is opened by designers, admins and the master admin."
  },
  {
    /*
      THE INSPECTOR'S OWN READ SURFACE, and the fourth top-level page in this family — which is why
      its row was written in the same change as the page rather than owed afterwards. The three
      before it (`/design-workshops`, `/design-review`, `/sketches-and-prototypes`) each shipped
      with the nav entry hidden and the URL open, by a maintainer who had read this table and found
      nothing beside the design-workshop tree.

      IT IS A SIBLING OF `/design-workshops` AND NOT A CHILD, and that is a permission fact rather
      than a filing one. The API's prefix is separate for the same reason: every caller of every
      route on it is, by definition, somebody `load_workshop_or_404` turns away, and a route sharing
      the workshop prefix invites the next reader to "fix" the inconsistency by widening that shared
      loader — which grants STAGE WRITES, because `load_workshop_or_404(for_edit=True)` performs no
      role check at all. `routeMatches` compares whole segments, so the `/design-workshops` row
      above does not reach this path and could not be made to without pointing the two at one gate.

      THE PREDICATE IS A ONE-MEMBER SET AND EVERY RANK INSTINCT IS WRONG ABOUT IT. An ADMIN is
      REFUSED here, and a master admin is refused, and that is not this table narrowing something
      the API would serve — `assert_inspection_surface` answers them a 403 by name. See
      `canInspectDesignWorkshops` for the argument; the short version is that an admin scoped by
      their own inspection rows sees an empty page and reads it as a broken feature, and an admin
      scoped by "everything" turns this into a second full read of the archive.

      SO THE MESSAGE NAMES THE OTHER DOOR, mirroring the server's `NOT_AN_INSPECTOR_DETAIL`, and it
      has to: a refusal that says only "you may not" to an admin — on a READ surface, in a product
      where admins read everything — reads as a broken deployment rather than as a rule.
    */
    path: "/design-workshop-inspections",
    can: canInspectDesignWorkshops,
    gate: "assert_inspection_surface (INSPECTION_ROLES, services/design_workshop_inspectors.py)",
    title: "Inspector / Reviewer access required",
    message:
      "The inspection surface belongs to the Inspector / Reviewer tier, and is scoped to the workshops an admin has assigned to that account. Designers and admins read design & prototype workshops on Design workshops instead; an admin chooses who inspects a workshop on Manage workshop access."
  },
  {
    path: "/design-workshops",
    can: canRunDesignWorkshops,
    gate: "can_run_design_workshops",
    title: "Designer access required",
    message:
      "A design & prototype workshop is a fortnight of a named designer's work that ends in a report submitted under their name, so it is run by designers, admins and the master admin."
  },
  {
    /*
      Custom questionnaires — PLURAL, and the plural matters here more than anywhere else in this
      table. `/questionnaire` (singular) is the ONE global artisan questionnaire, it is open to every
      signed-in user, and it must stay that way. `routeMatches` compares whole segments, so this rule
      cannot reach it: "/questionnaire" is neither equal to "/questionnaires" nor prefixed by
      "/questionnaires/". Written down because the two paths differ by one character and a future
      rule spelled with the singular would silently lock every researcher out of taking an interview.

      EVERY route in backend/app/api/routes/questionnaire_forms.py begins with `_require_designer`,
      which is `can_run_design_workshops` — the same SET (Designer, Admin, Master Admin) the design
      workshops use, not a rank threshold, so a professor is outside it. The OWNER-only half of the
      server's rule (`_require_owner`, for changing a questionnaire's questions) is deliberately NOT
      mirrored here: reading the form and recording answers against it are open to any designer, and
      a route guard that demanded ownership would lock a colleague out of the page they were handed
      the form to fill in. That half is enforced per control on the page itself.
    */
    path: "/questionnaires",
    can: canRunDesignWorkshops,
    gate: "can_run_design_workshops (_require_designer)",
    title: "Designer access required",
    message:
      "A custom questionnaire is a research instrument a designer builds for their own workshop, so building one and recording answers against it belongs to designers, admins and the master admin. The repository's shared artisan questionnaire is on Take interview, and it is open to everyone."
  },
  {
    // Gated with the workshops rather than left open, and the ENDPOINT was tightened to match in
    // the same change (`require_designer` in backend/app/core/deps.py). A guard here over an open
    // route would be a lock on a door with no wall — it hides the link and leaves the URL — which
    // is precisely the state this page was in.
    path: "/designers/profile",
    can: canRunDesignWorkshops,
    gate: "require_designer",
    title: "Designer access required",
    message:
      "A designer profile is the name, institution and biography a workshop report is submitted under. It belongs to designers, admins and the master admin."
  },
  { path: "/artisans/new", ...RECORD_CREATOR_GUARD },
  { path: "/products/new", ...RECORD_CREATOR_GUARD },
  { path: "/tools/new", ...RECORD_CREATOR_GUARD }
];

/**
 * Whether one rule covers one pathname, segment by segment.
 *
 * TWO BEHAVIOURS, AND THE SECOND IS NEW. A rule still covers its own path and everything beneath it,
 * which is what lets `/design-workshops` gate every page of every workshop with one row. What it can
 * now also do is name a VARIABLE segment as `:something`, which matches exactly one segment of any
 * value — needed the moment a rule has to sit at a path with an id in the middle of it, as
 * `/design-workshops/:id/provenance` does.
 *
 * WHY SEGMENT-WISE AND NOT A REGEX. The old implementation was `startsWith(rulePath + "/")`, which
 * is a STRING prefix and not a PATH prefix: `/data` would have covered `/database` had such a route
 * existed. Comparing segments removes that class of accident entirely, and it is the reason
 * `/questionnaire` and `/questionnaires` cannot reach each other — a property the table's own note
 * relies on and which was previously true only because neither is a string prefix of the other.
 */
function routeMatches(rulePath: string, pathname: string): boolean {
  const rule = rulePath.split("/").filter(Boolean);
  const actual = pathname.split("/").filter(Boolean);
  // A rule may be shorter than the path (it covers everything beneath it) but never longer.
  if (actual.length < rule.length) return false;
  return rule.every((segment, index) => segment.startsWith(":") || segment === actual[index]);
}

/** The most specific guard covering `pathname`, or null when the route is open to any signed-in user. */
export function routeGuardFor(pathname: string): RouteGuard | null {
  let best: RouteGuard | null = null;
  for (const guard of ROUTE_GUARDS) {
    if (!routeMatches(guard.path, pathname)) continue;
    if (!best || guard.path.length > best.path.length) best = guard;
  }
  return best;
}

export function canAccessRoute(user: User | null | undefined, pathname: string): boolean {
  const guard = routeGuardFor(pathname);
  return !guard || guard.can(user);
}

/* ────────────────────────────────────────────────────────────────────────────
 * Route redirects — the other half of gating, for pages that HAVE an ordinary-user twin.
 *
 * A ROUTE_GUARDS entry answers "no" with a lock panel, which is the honest answer when the page has
 * no counterpart: there is no ordinary-user version of /users or of the managed API keys, so naming
 * the tier and offering the dashboard is all that can truthfully be said.
 *
 * It is the WRONG answer when the same job also exists for the person asking. Workshop access is the
 * case in point: the admin console and the request page are two views of one WorkshopAssignment row,
 * so a researcher who opens "Workshop access" wants the half that belongs to them, and stopping them
 * at a padlock hides a page they are fully entitled to. These rules therefore send them to it —
 * `to` must always be a route the user can genuinely use, so this can never dead-end.
 *
 * Not listed, deliberately, because nothing executes the rule for them yet (declaring a redirect
 * that no one performs would read as enforcement that is not there):
 *  - /data → /search. The guard copy already says "browse records instead" but gives no way there.
 *  - /settings/tasks → /tasks. Same shape: the assignment board's twin is your own task list.
 * Both become one-liners here the moment AppShell consults this table — see routeRedirectFor.
 * ──────────────────────────────────────────────────────────────────────────── */

export type RouteRedirect = {
  /** The path this rule covers, together with everything nested beneath it. */
  path: string;
  /** Who sees the page as built. Everyone else is sent to `to` — this never widens `can`. */
  can: (user: User | null | undefined) => boolean;
  /** The ordinary-user route that does the same job for the person being turned away. */
  to: string;
};

export const ROUTE_REDIRECTS: RouteRedirect[] = [
  {
    path: "/workshop-access/manage",
    can: isAdmin,
    to: "/workshop-access/request"
  }
];

/**
 * Where `pathname` should send this user instead, or null when they may stay.
 *
 * Enforced today by the /workshop-access pages themselves. AppShell is the right place for it —
 * above every page, the way ROUTE_GUARDS is — and adopting it there is a `router.replace` on the
 * result of this call, checked BEFORE the ROUTE_GUARDS lock so a route with a twin redirects rather
 * than locks. Until then the table stays limited to routes that enforce it locally.
 */
export function routeRedirectFor(user: User | null | undefined, pathname: string): string | null {
  let best: RouteRedirect | null = null;
  for (const rule of ROUTE_REDIRECTS) {
    if (!routeMatches(rule.path, pathname)) continue;
    if (!best || rule.path.length > best.path.length) best = rule;
  }
  return best && !best.can(user) ? best.to : null;
}

/**
 * RUN a design & prototype workshop — open it, fill its 22 stages, create records inside it and
 * generate its report: Designer, Admin, Master Admin.
 *
 * NOT "start a new one". That is {@link canCreateDesignWorkshops}, a strictly narrower set, and
 * the two are separate functions on purpose — see there. Everything a designer has ever been able
 * to do inside a workshop is still this predicate.
 *
 * Mirrors `can_run_design_workshops` in backend/app/core/deps.py. Deliberately not
 * `canCreateRecords`: a design workshop ends in a document submitted under a named designer's
 * name, so the app should not invite somebody who cannot sign it to work on one.
 */
/**
 * Who may run a design & prototype workshop — and the ONE capability here that is not a rank
 * threshold.
 *
 * Every other predicate in this file reads "this tier and above", because the ladder is
 * inclusive. This one is a SET, so a PROFESSOR cannot run a design workshop even though they
 * outrank a designer. A workshop is a fortnight of a named designer's work ending in a document
 * submitted under their name; being senior to a designer is not being one. Admins are here so
 * somebody can administer and correct the records, not because they outrank anybody.
 *
 * `backend/app/core/deps.py::DESIGN_WORKSHOP_ROLES` carries the identical set and must keep
 * carrying it — the UI offering what the API refuses is exactly what the rank table above is
 * commented about, and a non-monotonic rule drifts far more easily than a threshold.
 */
export const DESIGN_WORKSHOP_ROLES: readonly UserRole[] = ["DESIGNER", "ADMIN", "MASTER_ADMIN"];

export function canRunDesignWorkshops(user: User | null | undefined) {
  return !!user && DESIGN_WORKSHOP_ROLES.includes(user.role);
}

/**
 * Who may bring a NEW design & prototype workshop into existence — a STRICT SUBSET of
 * {@link DESIGN_WORKSHOP_ROLES}, and the one place in this file where a DESIGNER is refused
 * something a designer used to have.
 *
 * THE RULE, AS IT WAS ASKED FOR: "designers cannot create workshops (only admins/master admins
 * can) — designers create records under existing workshops."
 *
 * WHY THE TWO PREDICATES ARE SEPARATE FUNCTIONS RATHER THAN ONE WITH A FLAG. They answer different
 * questions about different things. `canRunDesignWorkshops` asks "may this account do the work of
 * a workshop", and it gates a whole route tree; this asks "may this account open a NEW one", and
 * it gates a single control. Collapsing them is how a future edit to one silently moves the other
 * — and moving this one the wrong way costs a designer their fortnight of stage edits, which is far
 * worse than this rule is worth.
 *
 * `backend/app/core/deps.py::DESIGN_WORKSHOP_CREATOR_ROLES` carries the identical set and must keep
 * carrying it; `backend/tests/test_design_workshop_gate.py` reads THIS FILE to check that it does.
 */
export const DESIGN_WORKSHOP_CREATOR_ROLES: readonly UserRole[] = ["ADMIN", "MASTER_ADMIN"];

export function canCreateDesignWorkshops(user: User | null | undefined) {
  return !!user && DESIGN_WORKSHOP_CREATOR_ROLES.includes(user.role);
}

/**
 * Who may READ design-workshop stage data on the RESEARCH surfaces — the design-workshop taxonomy
 * and sheets in View Data, and the design-workshop bucket of Search.
 *
 * A NEW CAPABILITY BESIDE {@link DESIGN_WORKSHOP_ROLES}, NOT A WIDENING OF IT, and the two sets are
 * almost opposites: that one holds DESIGNER and refuses PROFESSOR, this one holds PROFESSOR and
 * refuses DESIGNER. That is not a contradiction, it is two different acts. Running a workshop is
 * writing inside somebody's fortnight of work; this is reading a table of what a corpus of them
 * recorded. A professor who gains this gains nothing at all inside any workshop, and a designer
 * reaches their OWN workshops through a per-record grant rather than through a door onto every
 * workshop in the repository.
 *
 * IT IS NOT `canDownloadDataset`, WHICH IS THE GATE ON THE SCREEN IT APPEARS ON. That predicate is
 * "Professor and above, OR the grantable `canDownloadDataset` flag", and the flag is the whole
 * difference: it is handed to a RESEARCHER who needs the seven legacy tables for a piece of work
 * and carries no seniority. Design-workshop stage data — artisan dictation, consent decisions,
 * unpublished prototype work — is gated on RANK, so a researcher holding the flag browses View Data
 * exactly as they do today and simply never meets a design-workshop folder, sheet or bucket.
 *
 * Owner ruling, 2026-08-30: "professor can view data for design workshops as well, admins and
 * master admins can download and view it too."
 * `backend/app/core/deps.py::DESIGN_WORKSHOP_DATA_VIEW_ROLES` carries the identical set and must
 * keep carrying it. See `docs/DECISION-design-workshop-data-in-view-data.md`.
 */
export const DESIGN_WORKSHOP_DATA_VIEW_ROLES: readonly UserRole[] = [
  "PROFESSOR",
  "ADMIN",
  "MASTER_ADMIN"
];

export function canViewDesignWorkshopData(user: User | null | undefined) {
  return !!user && DESIGN_WORKSHOP_DATA_VIEW_ROLES.includes(user.role);
}

/**
 * Who may TAKE design-workshop stage data out of the product — the .xlsx workbook, a CSV, the
 * whole-repository archive. Admin and Master Admin; a PROFESSOR is deliberately not here.
 *
 * THE SPLIT IS THE POINT, AND IT IS THE REASON THIS IS A SECOND PREDICATE RATHER THAN A FLAG ON THE
 * FIRST. There is a real population — professors — that reads a table on screen and may not export
 * the same rows, which is narrower than `/data`'s single `canDownloadDataset` gate has ever been
 * for the seven legacy tables. A screen is a reading; a file is a copy that leaves the building.
 *
 * SO EVERY SURFACE THAT OFFERS AN EXPORT BESIDE THOSE ROWS MUST SAY SO WHERE IT APPLIES. Handing a
 * professor a download button that answers 403 teaches them the product is broken rather than that
 * the rule exists — this file's standing rule is that the UI never offers what the API refuses, and
 * a button that appears and then fails is the loudest possible way to break it.
 *
 * `backend/app/core/deps.py::DESIGN_WORKSHOP_DATA_EXPORT_ROLES` is its twin.
 */
export const DESIGN_WORKSHOP_DATA_EXPORT_ROLES: readonly UserRole[] = ["ADMIN", "MASTER_ADMIN"];

export function canExportDesignWorkshopData(user: User | null | undefined) {
  return !!user && DESIGN_WORKSHOP_DATA_EXPORT_ROLES.includes(user.role);
}

/**
 * What a designer is told when they try to start a workshop — ONE sentence, in ONE place, because
 * it is said on four surfaces: the list page's panel, the offline draft store's refusal, the
 * server's 403 (`backend/app/core/deps.py::DESIGN_WORKSHOP_CREATE_REFUSAL`) and any dialog that
 * grows out of them. A refusal that names a different next move depending on where you met it is
 * not a rule, it is three rumours.
 *
 * IT NAMES WHO CAN CREATE ONE AND WHAT TO DO INSTEAD, and neither half is decoration. The person
 * reading it is standing in a courtyard with participants in front of them: "you do not have
 * permission" tells them to stop working, when the truth is that everything they came to do still
 * works the moment an admin has opened the workshop. A greyed-out button says even less than that.
 */
export const DESIGN_WORKSHOP_CREATE_REFUSAL =
  "Only admins and the master admin can start a new design & prototype workshop. Ask an admin to " +
  "create it for your cluster and give you access — you can then fill in all 22 stages, add " +
  "artisans, products and photographs, and generate the report exactly as before. Any workshop " +
  "you already have access to is open to you now.";

/**
 * THE FIFTH SCOPE'S DOOR: who may open the inspector's own read surface.
 *
 * A SET WITH ONE MEMBER, mirroring `INSPECTION_ROLES` in
 * `backend/app/services/design_workshop_inspectors.py`, which is `frozenset({"INSPECTOR"})`.
 *
 * **IT IS NOT "INSPECTOR AND ABOVE", AND THE RANK LADDER IS EXACTLY WHAT MISLEADS HERE.** 37 sits
 * between DESIGNER and PROFESSOR, so every threshold instinct admits professors, admins and the
 * master admin. `assert_inspection_surface` refuses all of them with a 403 — **including admins**,
 * deliberately, and its own docstring gives the argument: scoped by THEIR OWN inspection rows an
 * admin sees an empty list and reads it as a broken feature, and scoped by "everything, because
 * they are an admin" this surface silently becomes a second full read of every workshop in the
 * repository, which is a second place to look when somebody has access they should not. So the
 * server's refusal is identical for an admin, a designer and a volunteer, and this predicate is too.
 *
 * WHAT AN ADMIN GETS INSTEAD is the administration of who inspects what — `GET`/`PUT
 * /design-workshop-inspections/{id}/inspectors`, behind `require_admin`, rendered by
 * `components/settings/DesignWorkshopInspectorsPanel.tsx` on /workshop-access/manage. Two halves of
 * one feature with two different doors, which is why they are two predicates: `isAdmin` gates the
 * assignment, this gates the reading, and neither one implies the other in either direction.
 *
 * AND IT IS NOT `canRunDesignWorkshops`. INSPECTOR is deliberately outside `DESIGN_WORKSHOP_ROLES`
 * — a frozenset and not a rank floor — so every `/design-workshops`-family route refuses an
 * inspector exactly as it refuses a professor. Gating this destination on that predicate would hide
 * the one surface the tier exists for from the only tier that can use it.
 */
export const INSPECTION_ROLES: readonly UserRole[] = ["INSPECTOR"];

export function canInspectDesignWorkshops(user: User | null | undefined) {
  return !!user && INSPECTION_ROLES.includes(user.role);
}

/** Add, suspend and restore designers on the roster that gates their sign-in: Admin and above. */
export function canManageDesignerRoster(user: User | null | undefined) {
  return isAdmin(user);
}

/**
 * Decide who may sign in to this application AT ALL, and work the queue of people asking to: Admin
 * and above. Mirrors `can_manage_access_roster` in backend/app/core/deps.py.
 *
 * THE SAME TIER AS THE DESIGNER ROSTER, for a stronger version of the same reason: this list is
 * every address that may reach the product, so whoever can edit it can lock everybody else out,
 * including each other. It is not a professor's job.
 *
 * READ IS GATED WITH WRITE, and that is not an oversight to tidy up later. The pending queue is a
 * list of people who tried to get in — somebody's colleagues, applicants and former staff — so
 * browsing it is administrative work as much as deciding it is.
 *
 * A SEPARATE FUNCTION FROM {@link canManageDesignerRoster} although both are `isAdmin` today. They
 * mirror two different server predicates over two different tables, and collapsing them into one
 * would mean the day either server gate moves, the other client surface moves with it silently.
 */
export function canManageAccessRoster(user: User | null | undefined) {
  return isAdmin(user);
}
