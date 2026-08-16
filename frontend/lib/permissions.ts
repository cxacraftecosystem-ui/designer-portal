import type { User, UserRole } from "@/lib/types";

/**
 * The six-tier role ladder, mirroring the backend exactly (app/core/deps.py).
 * Higher rank inherits every power of the ranks below it; the grantable can*
 * booleans additionally lift a single capability for a lower tier.
 */
export const ROLE_RANK: Record<UserRole, number> = {
  CROWDSOURCE_VOLUNTEER: 10,
  FIELD_CONTRIBUTOR: 20,
  RESEARCHER: 30,
  // 35, in the gap the original tens left. Mirrors ROLE_RANK in backend/app/core/deps.py; the
  // two must agree or the UI offers actions the API refuses.
  DESIGNER: 35,
  PROFESSOR: 40,
  ADMIN: 50,
  MASTER_ADMIN: 60
};

export const ROLE_LABELS: Record<UserRole, string> = {
  CROWDSOURCE_VOLUNTEER: "Crowdsource Volunteer",
  FIELD_CONTRIBUTOR: "Field Contributor",
  RESEARCHER: "Researcher",
  DESIGNER: "Designer",
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
