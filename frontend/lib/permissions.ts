import type { User, UserRole } from "@/lib/types";

/**
 * The ELEVEN-tier role ladder, mirroring the backend exactly (app/core/deps.py).
 * Higher rank inherits every power of the ranks below it; the grantable can*
 * booleans additionally lift a single capability for a lower tier.
 *
 * ELEVEN SINCE 2026-09-13, when ASSISTANT_DIRECTOR (42), REGIONAL_DIRECTOR (45) and
 * MINISTRY_ADMIN (48) were inserted between PROFESSOR and ADMIN; EIGHT since 2026-08-27, when
 * INSPECTOR (37) was inserted between DESIGNER and PROFESSOR. The
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
 * re-counted itself. It shipped the identical miss a second time — "Eight-tier access control" past
 * the three directorate tiers of 2026-09-13, corrected by hand on 2026-09-14 after a review found
 * it — so it now reads Eleven and its note says, in as many words, that the comment has failed
 * twice and that the repair is to move `TIER_COUNT_WORD` into a motion-free module rather than to
 * write a better warning. What is
 * left is outside this client: `docs/PERMISSIONS.md`
 * and `SESSION_HANDOVER.md` say "six-tier" only to narrate that correction, `docs/RESEARCH_NOTES.md`
 * keeps a provenance-labelled six-row snapshot on purpose and says so, and the live miscount is
 * now ZERO. The last one standing was `MainActivity.kt` in the Android client, which said
 * "six-tier ladder" twice — once in RENDERED SCREEN COPY on the users-and-access card
 * ("Professors and above can move a user along the six-tier ladder"), which was the worst of the
 * lot because a user read it, and once in the comment above the role dropdown on that same card,
 * whose options are built from `ROLE_RANK` and so already listed eight. Both were corrected to
 * "eight-tier" on 2026-08-27, the comment carrying the count's source with it, and to
 * "eleven-tier" on 2026-09-13.
 * `ui/AppNavigation.kt` (now "The ELEVEN-tier ladder"),
 * `backend/.env.example` and the frontend skill file agents load,
 * `.claude/skills/field-repo-frontend/SKILL.md`, were all on this list
 * and were each corrected in the INSPECTOR wave.
 *
 * TWO OF THOSE THREE WENT BACK ON THE LIST ON 2026-09-13, and they are named rather than silently
 * quoted, because this paragraph is the only place that tracks them.
 * `.claude/skills/field-repo-frontend/SKILL.md` §14.2 is OFF it again as of 2026-09-14: it now reads
 * "**Eleven**-tier ladder" and enumerates all eleven ranks, which matters more than the other
 * entries because every agent is told to load that file before any frontend work, so its count is
 * the upstream source of the next fourteen copies of whatever it says.
 * **`backend/.env.example:184` IS STILL ON IT** — it reads "eight tiers as of 2026-08-27", it is
 * one word, and it was left standing on 2026-09-14 only because the lane that swept the rest was
 * scoped out of `backend/`. It is read by no check. `backend/app/core/config.py` was on it
 * and should not have been: its "pre-six-tier behavior" dates an ERA, not the present ladder.
 * RE-SWEPT 2026-09-14 with `grep -rniE "(eight|seven|six|nine|ten)[- ]tier|... tiers"` over every
 * `.ts/.tsx/.py/.kt/.md/.mjs` in the tree: the live miscounts found and corrected that day were this
 * client's `app/login/page.tsx` (RENDERED), `components/admin/rosterFilters.ts` (three comments, one
 * of which had changed MEANING — eleven tiers plus the reserved row is twelve options, no longer
 * "exactly `SEARCH_THRESHOLD`"), `components/hero/AccessLadder.tsx`'s "a NINTH tier" ordinal,
 * `docs/README.md`'s index row, `docs/RESEARCH_NOTES.md`'s correcting sentence (the snapshot itself
 * was correctly labelled; it was the sentence saying "the ladder is now eight" that had rotted), the
 * frontend skill file, and dated notes on `DROPDOWN_DESIGN.md` and `IMPLEMENTATION_PLAN.md`.
 * `RECON_FINDINGS.md` was deliberately left alone: it pins itself to commit `7c60e81` in its own
 * first sentence, so its counts are a record and re-counting them would destroy it — the same rule
 * `docs/tools/check-docs.mjs` applies to `AUDIT-2026-08-15.md`. Three backend test files
 * (`test_access_roster.py`, `test_design_workshop_data_access.py`, `test_role_ladder_parity.py`)
 * still carry "eight tiers" in prose or in an assertion message and were out of that lane's scope.
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
 * the opposite. `ROLES_BY_RANK` below sorts on the VALUES, and all eleven ranks are distinct, so the
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
  // The three DIRECTORATE tiers, added 2026-09-13 into the free 41-49 band between PROFESSOR and
  // ADMIN. 42/45/48 is the one arrangement of three that leaves a gap on both sides of each and
  // keeps every adjacent pair two apart; the server's `ROLE_RANK` carries the full argument.
  //
  // WHAT THEY CHANGE IN THIS CLIENT. Every `hasRank(user, "PROFESSOR")` predicate below answers true
  // for all three at once — canManageUsers, canManageCrafts, canManageWorkshops,
  // canManageQuestionnaire, canDownloadDataset and canSeeDataTile. `isAdmin` and `isMasterAdmin` are
  // set membership and answer false, so nothing in the /admin tree opens, and every design-workshop
  // control stays shut because those are SETS. The one set they were added to deliberately is
  // DESIGN_WORKSHOP_DATA_VIEW_ROLES further down, which is read-on-screen and not export.
  ASSISTANT_DIRECTOR: 42,
  REGIONAL_DIRECTOR: 45,
  MINISTRY_ADMIN: 48,
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
  // Byte for byte the server's ROLE_LABELS; `frontend/e2e/role-ladder-parity-unit.spec.ts` diffs the
  // spelling. Plain titles, no slash — unlike INSPECTOR, there is no vocabulary collision here.
  ASSISTANT_DIRECTOR: "Assistant Director",
  REGIONAL_DIRECTOR: "Regional Director",
  MINISTRY_ADMIN: "Ministry Admin",
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

/**
 * Only admins and the master admin may assign tasks to other users.
 *
 * A FLOOR WHERE THE SERVER IS A SET. `deps.require_admin` is `is_admin`, which is set membership on
 * MASTER_ADMIN and ADMIN; this is `rank >= 50`. The two agree for every tier that exists, because
 * the three directorate tiers added 2026-09-13 are 42/45/48 and all below 50. A tier added ABOVE 50
 * would make them disagree and would hand it the whole task-assignment surface on this client alone,
 * where the API would then refuse it — the exact "UI offers what the API refuses" failure this file
 * is written against. If that day comes, this becomes a set.
 */
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

/**
 * EDIT a workshop — `require_workshop_manager`. Professor+; deleting one is admin-only.
 *
 * IT SAID "ADD OR EDIT" UNTIL 2026-09-16 AND THE "ADD" HALF MOVED. `POST /workshops` is now
 * `require_workshop_opener`, a rank floor at MINISTRY_ADMIN, because R6 of the dropdown ruling is
 * that designers participate in workshops and the ministry and admin tiers open them — and that
 * floor also takes the create away from professors, assistant directors and regional directors, who
 * all keep the edit. {@link canCreateWorkshops} is the create predicate; this is the edit one, and
 * the two are separate functions precisely because they are now separate rules.
 */
export function canManageWorkshops(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR");
}

/**
 * OPEN a new workshop — `require_workshop_opener` (backend/app/api/routes/workshops.py).
 *
 * MINISTRY_ADMIN and above: `{MINISTRY_ADMIN, ADMIN, MASTER_ADMIN}`. The refusal a designer reads is
 * the server's own `WORKSHOP_CREATE_REFUSAL`, printed verbatim by
 * `app/(protected)/workshops/page.tsx` and by the handset's refusal card — never re-worded on a
 * client, because three copies of one sentence is not a rule, it is three rumours.
 *
 * A TWIN OF {@link canManageWorkshops} AND NOT A NARROWING OF IT. The edit gate genuinely is looser
 * and that is the ruling: opening a workshop is the ministry's act, correcting one somebody else
 * opened is ordinary repository work. A page that wants "may this account reach the form at all"
 * wants `canManageWorkshops && (editing || canCreateWorkshops)`, which is what the workshops page
 * spells out, because a professor must still reach every field through Edit.
 *
 * NOT THE SAME AS `canCreateDesignWorkshops`, which is `{ADMIN, MASTER_ADMIN}` on the other workshop
 * table — a MINISTRY_ADMIN opens a `Workshop` here and reaches a `DesignWorkshop` through
 * `POST /annual-plan/{id}/promote`. Two doors, two gates, one creation path.
 */
export function canCreateWorkshops(user: User | null | undefined) {
  return hasRank(user, "MINISTRY_ADMIN");
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
 *
 * ONE COLUMN ON THIS TABLE IS NOT ABOUT GATING AT ALL — `ministry?`, read by {@link ministrySurface}
 * and by nothing else. It is here rather than in a list of its own because this table is already the
 * register of "which routes are which", and a second list of ministry paths is a second list to go
 * stale. It changes who may open nothing.
 * ──────────────────────────────────────────────────────────────────────────── */

export type RouteGuard = {
  /** The path this rule covers, together with everything nested beneath it. */
  path: string;
  can: (user: User | null | undefined) => boolean;
  /** The backend dependency this mirrors (backend/app/core/deps.py) — keep the two in step. */
  gate: string;
  title: string;
  message: string;
  /**
   * This route is a MINISTRY-ONLY SURFACE, and AppShell paints it as one.
   *
   * A flag on the row, and that is the only correct derivation — see {@link ministrySurface}. It is
   * the same idiom `NAV_ITEMS` uses for `adminSurface?`: a fact about the route, declared beside the
   * route, so the register stays single and there is no second list of paths to go stale.
   *
   * IT IS A FACT ABOUT A SCREEN, NOT ABOUT A VIEWER. There is no "is this person ministry"
   * predicate in this file and there must not be one. The ministry surfaces are gated by FIVE
   * different rules and each one's docstring below argues why it could not have been any of the
   * others: {@link canManageAnnualPlan} is a rank floor at 48, `canRecordSanctionOrders`
   * (lib/sanctionOrders.ts) a rank floor at 42, {@link canAssignWorkshopOversight} a SET that
   * refuses a Regional Director who outranks an Assistant Director, {@link canReadWorkshopOversight}
   * a set that refuses an ADMIN by name, and {@link canSeeMinistryDesk} a card audience with a hole
   * at ADMIN(50) and MASTER_ADMIN(60) above it. They are non-monotonic in rank in different
   * directions; no sixth function reconciles them. This flag says only that whoever the row does
   * admit is being admitted to ministry work.
   */
  ministry?: boolean;
};

/**
 * Shared copy for the three create ROUTES, mirroring `require_record_creator`'s 403 detail.
 *
 * THREE ROUTES, FOUR RECORD TYPES, and the message below names all four on purpose. `/artisans/new`,
 * `/products/new` and `/tools/new` are real pages; a process is created by an INLINE form on
 * `/processes` (`?new=1`), so it has no `/new` route to guard and the same predicate reaches it
 * through the page. This line read "the four create routes" until 0.0.12, which sent a reader
 * looking for a fourth row that has never existed.
 */
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
    /*
      The ministry's annual directory of planned workshops.

      A TOP-LEVEL ROUTE AND NOT `/admin/…`, AND THAT IS A DECISION RATHER THAN A FILING PREFERENCE.
      `/admin` gates on `isAdmin`, which is SET membership ({ADMIN, MASTER_ADMIN}) on both sides of
      the wire, and the hub page itself re-checks it in the component — so nesting this under it
      would refuse MINISTRY_ADMIN, the one tier the directory exists for, twice over: once at the
      guard and once at the shell. It is the same trap `/sanction-orders` two rows down avoids, and
      for the same reason: a rule that is WIDER than `/admin` must not sit underneath it.

      READ IS THE SAME GATE AS WRITE. The plan is a list of named places and dates the ministry has
      not announced yet, so reading it is administrative work as much as correcting it is — the same
      reasoning the designer roster's and the access roster's rules carry above.
    */
    path: "/annual-plan",
    can: canManageAnnualPlan,
    gate: "require_annual_plan_manager",
    ministry: true,
    title: "Ministry administrator access required",
    message:
      "The annual plan of workshops — the ministry's directory of what is to be held this year, and where — is uploaded and corrected by the ministry administrator and above. Workshops that have already been opened are on Design workshops."
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
    /*
      THE SIXTH SCOPE, HALF ONE — THE ASSIGNMENT SCREEN. A Ministry Admin names a workshop's
      designer, its Assistant Director and its Regional Director here, and uploads its artisan list.

      ITS ROW WAS WRITTEN IN THE SAME CHANGE AS THE PAGE rather than owed afterwards. Four pages in
      this family have now shipped with the nav entry hidden and the URL open —
      `/design-workshops`, `/design-review`, `/sketches-and-prototypes` and, until the inspector
      wave, the inspection surface — each by a maintainer who had read this table and found nothing
      beside the design-workshop tree. A hidden nav entry has never been a guard.

      A SIBLING OF `/design-workshops` AND NOT A CHILD, which is a permission fact rather than a
      filing one, and the API's prefix is separate for the same reason: every caller of every route
      on `/design-workshop-oversight` is by definition somebody `load_workshop_or_404` turns away,
      and a route sharing the workshop prefix invites the next reader to "fix" the inconsistency by
      widening that shared loader — which grants STAGE WRITES. `routeMatches` compares whole
      segments, so the `/design-workshops` row cannot reach this path.

      A DESIGNER IS REFUSED, WHICH IS THE ROW'S ENTIRE CONTENT, and so is a REGIONAL DIRECTOR even
      though they outrank an Assistant Director. See `canAssignWorkshopOversight`.
    */
    path: "/officers",
    can: canAssignWorkshopOversight,
    gate: "assert_may_assign_oversight (OVERSIGHT_ASSIGNER_ROLES, services/design_workshop_oversight.py)",
    ministry: true,
    title: "Ministry Admin access required",
    message:
      "Naming the designer, the Assistant Director and the Regional Director on a design & prototype workshop — and uploading that workshop's artisan list — is done by a Ministry Admin, an admin or the master admin. An Assistant Director or Regional Director reads the workshops they have been assigned on Workshops I monitor."
  },
  {
    /*
      THE SIXTH SCOPE, HALF TWO — THE OFFICER'S OWN READ SURFACE.

      DECLARED AFTER `/officers` AND THAT ORDER DOES NOT MATTER, because `routeGuardFor` picks the
      LONGEST matching path rather than the first: `/officers/monitored` is longer than `/officers`,
      so it wins for this page and for every id beneath it. Said out loud because the two rules gate
      DISJOINT audiences — an admin may reach the first and not the second, an Assistant Director the
      second and not the first — so a reader who assumed first-match-wins would conclude the officer's
      own page refuses every officer.

      AN ADMIN IS REFUSED HERE and that mirrors the server rather than narrowing it —
      `assert_oversight_surface` answers them a 403 by name. So the message names the other door,
      which it has to: a refusal that says only "you may not" to an admin, on a READ surface, in a
      product where admins read everything, reads as a broken deployment rather than as a rule.
    */
    path: "/officers/monitored",
    can: canReadWorkshopOversight,
    gate: "assert_oversight_surface (OFFICER_ROLES, services/design_workshop_oversight.py)",
    ministry: true,
    title: "Officer access required",
    message:
      "Workshops I monitor lists the design & prototype workshops a Ministry Admin has assigned to this account as its Assistant Director or Regional Director. Designers and admins read design & prototype workshops on Design workshops instead; a Ministry Admin chooses who monitors a workshop on Workshop oversight."
  },
  {
    /*
      THE OFFICER'S REGISTER. A SIBLING OF THE WORKSHOP TREE AND NOT A CHILD OF /admin, and both
      halves of that are deliberate.

      NOT UNDER /admin, even though it is administrative in feel. `/admin` gates on `isAdmin`, the
      SET {ADMIN, MASTER_ADMIN}; this gates on rank >= 42, which is WIDER. `routeGuardFor` picks the
      longest matching path, so a nested rule would technically win — and the hub page itself would
      still refuse a ministry officer, leaving them a route they may open and no way to reach it. A
      wider rule nested under a narrower prefix is a trap the next reader has to re-derive.

      A SIBLING RATHER THAN A CHILD OF /design-workshops, for the same reason /design-review and
      /design-workshop-inspections are siblings: every caller here is by definition somebody
      `assert_can_create_design_workshops` turns away, and a shared prefix invites widening the set
      that gate reads.

      THE PREDICATE AND THE SENTENCE ARE WRITTEN OUT HERE rather than referenced, which is the one
      thing on this row that is not the house pattern. `lib/sanctionOrders.ts` exports the twin
      `canRecordSanctionOrders` and `SANCTION_ORDER_REFUSAL` that the nav entry and the page use,
      and it imports `hasRank` from THIS file — so a `can:` pointing back at that module would be an
      import cycle whose `const` half would land in the temporal dead zone. The two copies are held
      byte-for-byte equal by `backend/tests/test_sanction_order_gate.py`, which reads both files off
      disk; lifting them into an exported predicate here is a welcome follow-up, and the test is
      what makes doing it safe.
    */
    path: "/sanction-orders",
    can: (user) => hasRank(user, "ASSISTANT_DIRECTOR"),
    gate: "require_sanction_recorder",
    ministry: true,
    title: "Ministry officer access required",
    message:
      "Recording a sanction order is a ministry officer's act — Assistant Director and above. It opens a workshop, creates the designer's account and issues their sign-in link, so it is not something a designer or a professor can do for themselves. Ask the officer who holds the order to record it; the workshop will appear in your list as soon as they do."
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

/**
 * Is this screen one of the ministry's own? — the whole of the ministry surface's gating.
 *
 * `AppShell` asks this once per navigation and stamps `data-surface="ministry"` on <main>, which is
 * what the scoped block at the end of app/globals.css hangs off: the header's icon chip, a rule down
 * the left edge of every panel, the eyebrow. FOUR routes carry the flag today — /annual-plan,
 * /officers, /officers/monitored and /sanction-orders — and a fifth is one `ministry: true` on its
 * row, with no CSS, no component and no second list to touch.
 *
 * ── READ THE FLAG. DO NOT COMPARE `guard.can`. ──────────────────────────────────────────
 *
 * Deriving this by testing `guard.can` against the ministry predicates is the obvious shortcut and
 * it SILENTLY UNDER-REPORTS: /sanction-orders' `can` is an INLINE ARROW written out on the row
 * (`(user) => hasRank(user, "ASSISTANT_DIRECTOR")`, and its own comment explains that a reference to
 * `canRecordSanctionOrders` would be an import cycle through lib/sanctionOrders.ts), so an identity
 * comparison misses it, finds three of four, and the sanction register quietly stops being a
 * ministry surface with nothing on screen or in a type to say so. A flag on the row cannot do that.
 *
 * ── LONGEST MATCH, WHICH IS THE TABLE'S OWN RULE AND NOT A SEPARATE ONE ───────────────────
 *
 * It asks {@link routeGuardFor} rather than sweeping the table itself, so a path resolves to exactly
 * the row that GUARDS it: /officers/monitored/<id> is ministry because /officers/monitored is, and a
 * future narrower row nested under a ministry one would be able to opt its own subtree out simply by
 * not carrying the flag — the same override /officers/monitored already performs on /officers for a
 * disjoint audience. Two loops over one table would be two answers waiting to disagree.
 *
 * ── IT SAYS NOTHING ABOUT THE VIEWER ───────────────────────────────────────────────
 *
 * It takes a pathname and no user, deliberately. Whether the person looking may be HERE is
 * {@link canAccessRoute}'s question and is asked separately — AppShell only stamps the attribute on
 * a page it is actually serving, because the refusal panel is drawn for somebody who is not a
 * ministry account and the accent is not for them.
 *
 * ── /design-workshops IS NOT ONE OF THESE, AND THE MISTAKE IS EASY ───────────────────────
 *
 * It is the fifth row of the ministry DESK card, so "everywhere the desk points" reads like the
 * definition — and it is gated on {@link canRunDesignWorkshops}, i.e. it is the DESIGNERS' main
 * workspace. Flagging it would turn every designer's daily screen orange.
 * `frontend/e2e/ministry-surface-unit.spec.ts` fails on exactly that.
 */
export function ministrySurface(pathname: string): boolean {
  return routeGuardFor(pathname)?.ministry === true;
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
export const DESIGN_WORKSHOP_ROLES: readonly UserRole[] = [
  "DESIGNER",
  // THE THREE DIRECTORATE TIERS, added 2026-09-14 on the owner's ruling. They already read every
  // workshop; this is the WRITE. The gap it closes: a MINISTRY_ADMIN could promote an annual-plan
  // row into a workshop and then not save a stage in the workshop they had just created.
  //
  // STILL A SET AND NOT A FLOOR. These three sit above professor and PROFESSOR is deliberately
  // still out — being senior to a designer is not the same as being one.
  //
  // INSPECTOR IS DELIBERATELY ABSENT. It was asked for alongside these three and excluded on
  // purpose: this is the write set, and an inspector in it would author the stages it later
  // reviews. It reaches a workshop through its own workshop-scoped grant.
  "MINISTRY_ADMIN",
  "REGIONAL_DIRECTOR",
  "ASSISTANT_DIRECTOR",
  "ADMIN",
  "MASTER_ADMIN"
];

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
  // The three directorate tiers, added 2026-09-13. STILL A SET and not a floor: the tier just BELOW
  // professor is INSPECTOR (37), who reaches one workshop under a grant and must never acquire every
  // workshop in the repository because a number moved. What changed is that three tiers now sit
  // ABOVE professor, and a tier above the floor that reads less than the floor makes the ladder's own
  // inclusive claim false. `backend/app/core/deps.py::DESIGN_WORKSHOP_DATA_VIEW_ROLES` is the twin.
  //
  // THE EXPORT SET BELOW IS UNCHANGED, which is the half that keeps this honest: a directorate tier
  // reads these rows on screen and cannot take them out of the product, exactly as a professor
  // cannot.
  "ASSISTANT_DIRECTOR",
  "REGIONAL_DIRECTOR",
  "MINISTRY_ADMIN",
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

/**
 * THE SIXTH SCOPE'S DOOR, HALF ONE: who may name the designer, the Assistant Director and the
 * Regional Director on a design & prototype workshop — and who may upload that workshop's artisan
 * list.
 *
 * A SET, mirroring `OVERSIGHT_ASSIGNER_ROLES` in
 * `backend/app/services/design_workshop_oversight.py`, which is
 * `frozenset({"MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"})`.
 *
 * **A REGIONAL DIRECTOR IS REFUSED HERE, AND THEY OUTRANK AN ASSISTANT DIRECTOR.** Every rank
 * instinct is wrong about this predicate, which is why it is a set and not `hasRank(user,
 * "MINISTRY_ADMIN")` — the two happen to agree today and mean different things, and the day a tier
 * lands between 45 and 48 the floor would admit it silently. "The supervised must not choose the
 * supervisor" is the same rule `canInspectDesignWorkshops` states one rung down as "the inspected
 * must not choose the inspector". An RD who should be able to assign is a MINISTRY_ADMIN, which is a
 * role change an admin makes on /users — not a widening here.
 *
 * A DESIGNER IS REFUSED FOR THE SAME REASON, one rung the other way: a designer may not choose who
 * supervises, inspects or is named on their own workshop.
 */
export const OVERSIGHT_ASSIGNER_ROLES: readonly UserRole[] = [
  "MINISTRY_ADMIN",
  "ADMIN",
  "MASTER_ADMIN"
];

export function canAssignWorkshopOversight(user: User | null | undefined) {
  return !!user && OVERSIGHT_ASSIGNER_ROLES.includes(user.role);
}

/**
 * THE SIXTH SCOPE'S DOOR, HALF TWO: the officer's own read surface — the workshops a Ministry Admin
 * has assigned this account to supervise.
 *
 * A SET WITH THREE MEMBERS, mirroring `OFFICER_ROLES` in
 * `backend/app/services/design_workshop_oversight.py`.
 *
 * **AN ADMIN IS REFUSED HERE, AND THAT MIRRORS THE SERVER RATHER THAN NARROWING IT.**
 * `assert_oversight_surface` answers an ADMIN and a MASTER ADMIN a 403 by name, for the reason
 * `assert_inspection_surface` gives one scope over: scoped by their own oversight rows an admin sees
 * an empty page and reads it as a broken deployment, and scoped by "everything, because they are an
 * admin" this becomes a second full read of every workshop in the archive. So this is the second
 * route rule in this client whose refusal is not monotonic in rank, and §2's ladder gives the wrong
 * answer for it every time.
 *
 * MINISTRY_ADMIN IS IN BOTH SETS, deliberately: they choose who supervises a workshop AND they may
 * be assigned one. The two are different acts on different screens, and neither predicate is the
 * other's superset — an ADMIN may assign and may not be assigned.
 */
export const OFFICER_ROLES: readonly UserRole[] = [
  "ASSISTANT_DIRECTOR",
  "REGIONAL_DIRECTOR",
  "MINISTRY_ADMIN"
];

export function canReadWorkshopOversight(user: User | null | undefined) {
  return !!user && OFFICER_ROLES.includes(user.role);
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

/**
 * Read and correct the ministry's annual directory of planned workshops. Mirrors
 * `can_manage_annual_plan` in `backend/app/services/annual_plan.py` and the
 * `require_annual_plan_manager` dependency in `backend/app/api/routes/annual_plan.py`.
 *
 * A RANK FLOOR AT MINISTRY_ADMIN, AND NOT `isAdmin`. Read this before "simplifying" it, because the
 * two are not the same SHAPE. `isAdmin` here is set membership — `{"ADMIN", "MASTER_ADMIN"}`,
 * exactly as it is on the server — so a Ministry Admin at rank 48 is NOT admitted by it, and this
 * feature exists for that tier. Gating the directory on `isAdmin` would leave the ministry
 * administrator unable to open their own ministry's plan, behind a refusal that reads as a bug.
 *
 * REGIONAL_DIRECTOR (45) AND ASSISTANT_DIRECTOR (42) ARE DELIBERATELY BELOW THE FLOOR. The annual
 * plan is a national instrument issued once a year, and this table carries no per-region column an
 * edit could be narrowed to — so the rank change that admits a regional director admits them to the
 * whole of it. If regional editing is ever wanted it is a scope table, not a rank change. See
 * `can_manage_annual_plan`'s docstring for the same argument at greater length.
 *
 * READ IS THE SAME GATE AS WRITE, for the reason {@link canManageAccessRoster} gives about its own
 * queue: the plan is a list of named places and dates the ministry has not announced yet.
 */
export function canManageAnnualPlan(user: User | null | undefined) {
  return hasRank(user, "MINISTRY_ADMIN");
}

/**
 * WHO IS OFFERED THE MINISTRY DESK — the dashboard card that gathers the ministry surfaces into one
 * place. The three directorate tiers and the master admin, and nobody else.
 *
 * ── THIS IS A CARD'S AUDIENCE AND IT IS NOT AN ENTITLEMENT ───────────────────────────────────────
 *
 * Nothing here opens a door. Every destination the card links to keeps the predicate it already had
 * — {@link canManageAnnualPlan} on Annual plan, {@link canAssignWorkshopOversight} on Workshop
 * oversight, `canRecordSanctionOrders` (lib/sanctionOrders.ts) on Sanction orders,
 * {@link canReadWorkshopOversight} on Workshops I monitor, {@link canRunDesignWorkshops} on Design
 * workshops — and the card asks each one again, per row, so a row a viewer cannot open is not drawn.
 * Widening THIS predicate therefore widens no capability at all; it would only put a card with no
 * rows in it on somebody's dashboard. That separation is the whole reason it is a predicate of its
 * own rather than a reuse of one of the five: a launching surface and a capability are two different
 * questions, and answering them with one function is how a later edit to the launcher silently moves
 * the gate. {@link canSeeDataTile} above is the same shape for the same reason, and is likewise
 * mirrored nowhere — there is no server predicate for "which cards does this dashboard draw", and
 * inventing one in `deps.py` would be a gate that gates nothing.
 *
 * ── IT IS A SET, AND EVERY OTHER SHAPE IS WRONG HERE IN A DIFFERENT DIRECTION ────────────────────
 *
 * NOT `isAdmin`. That is set membership on {ADMIN, MASTER_ADMIN} on both sides of the wire, so it
 * admits exactly one member of this audience and refuses the three tiers the card exists for. It is
 * the trap the `/annual-plan` route rule above spends a paragraph on: a Ministry Admin's token says
 * admin and no predicate in this file agrees.
 *
 * NOT A RANK FLOOR. The tightest floor that admits ASSISTANT_DIRECTOR (42) also admits ADMIN (50),
 * and an ADMIN is deliberately OUT — see below. No floor produces this set, because the set has a
 * hole in it at 50 and every threshold instinct closes that hole.
 *
 * NOT `canRecordSanctionOrders`, which is the predicate that comes closest — Assistant Director and
 * above, i.e. 42, 45, 48, 50, 60 — and differs by exactly one tier. Borrowing it would put the card
 * on an admin's dashboard, and a set that happens to agree with another set for every tier that
 * exists today is precisely the drift this file's header is written against.
 *
 * ── WHY AN ADMIN IS EXCLUDED BY NAME, WHICH IS THE ONLY SURPRISING MEMBERSHIP HERE ───────────────
 *
 * An ADMIN can do most of what this card links to — they assign oversight, they read and correct the
 * annual plan, they record sanction orders — so their exclusion is not about capability and must not
 * be read as one. It is about which accounts have a HUB already. An admin has `/admin`, the settings
 * hub, and every one of these destinations in the nav sheet besides; the three directorate tiers
 * have neither, because `/admin` gates on `isAdmin` and refuses them at the route guard and again
 * inside the page. This card is the hub those three do not otherwise have. Handing it to an account
 * that already has one would be a fourth entrance to screens that already have three — the cost the
 * dashboard's own "NO DESIGNER ROSTER TILE HERE" note records.
 *
 * MASTER_ADMIN is in the set on the owner's instruction, and it earns its place rather than merely
 * obeying one: it is the account that must be able to see what a ministry officer sees without
 * holding a ministry post. It also demonstrates that the per-row gating is real rather than
 * decorative — a master admin is REFUSED `/officers/monitored` BY NAME on the server
 * (`assert_oversight_surface`), so that row is absent from their card and present on an Assistant
 * Director's.
 *
 * ── AND IT IS NOT ADMIN CHROME ──────────────────────────────────────────────────────────────────
 *
 * Deliberately not wrapped in the dashboard's `adminSurface` helper, for the reason the `/annual-plan`
 * nav entry gives in full: the admin-view toggle exists only for an account `isAdmin` admits, so
 * flagging this would hide the card from the ONE member of this set who has a toggle — the master
 * admin, browsing with admin view off — while leaving it on screen for the three tiers below them.
 * A rule that fires for exactly the wrong half of its audience.
 *
 * `backend/tests/test_role_ladder_parity.py` registers this literal as a `partial` mirror, so a
 * twelfth tier cannot default into or out of this audience by nobody having thought about it.
 */
export const MINISTRY_DESK_ROLES: readonly UserRole[] = [
  "ASSISTANT_DIRECTOR",
  "REGIONAL_DIRECTOR",
  "MINISTRY_ADMIN",
  "MASTER_ADMIN"
];

export function canSeeMinistryDesk(user: User | null | undefined) {
  return !!user && MINISTRY_DESK_ROLES.includes(user.role);
}
