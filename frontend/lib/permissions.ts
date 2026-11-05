import type { User, UserRole } from "@/lib/types";

export const ROLE_RANK: Record<UserRole, number> = {
  CROWDSOURCE_VOLUNTEER: 10,
  FIELD_CONTRIBUTOR: 20,
  RESEARCHER: 30,
  DESIGNER: 35,
  INSPECTOR: 37,
  PROFESSOR: 40,
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
  INSPECTOR: "Inspector / Reviewer",
  PROFESSOR: "Professor",
  ASSISTANT_DIRECTOR: "Assistant Director",
  REGIONAL_DIRECTOR: "Regional Director",
  MINISTRY_ADMIN: "Ministry Admin",
  ADMIN: "Admin",
  MASTER_ADMIN: "Master Admin"
};

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

export function assignableRoles(user: User | null | undefined): UserRole[] {
  return ROLES_BY_RANK.filter((role) => ROLE_RANK[role] <= roleRank(user));
}

export function canManageUser(user: User | null | undefined, target: User): boolean {
  if (isMasterAdmin(user)) return target.role !== "MASTER_ADMIN" || target.id === user?.id;
  return roleRank(target) < roleRank(user);
}

export function canManageUsers(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR");
}

export const ACCOUNT_PROVISIONER_ROLES: readonly UserRole[] = [
  "MINISTRY_ADMIN",
  "ADMIN",
  "MASTER_ADMIN"
];

export function canProvisionAccounts(user: User | null | undefined) {
  return !!user && ACCOUNT_PROVISIONER_ROLES.includes(user.role);
}

export function provisionableRoles(user: User | null | undefined): UserRole[] {
  if (!canProvisionAccounts(user)) return [];
  const own = roleRank(user);
  return ROLES_BY_RANK.filter((role) => ROLE_RANK[role] < own);
}

export function canAssignTasks(user: User | null | undefined) {
  return isContentAdmin(user);
}

export function isAdmin(user: User | null | undefined) {
  return user?.role === "MASTER_ADMIN" || user?.role === "ADMIN";
}

export function isMasterAdmin(user: User | null | undefined) {
  return user?.role === "MASTER_ADMIN";
}

export const CONTENT_ADMIN_ROLES: readonly UserRole[] = ["MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"];

export function isContentAdmin(user: User | null | undefined) {
  return !!user && CONTENT_ADMIN_ROLES.includes(user.role);
}

export function canManageQuestionnaire(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR") || !!user?.canManageQuestionnaire;
}

export function canManageCrafts(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR");
}

export function canManageWorkshops(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR");
}

export function canCreateWorkshops(user: User | null | undefined) {
  return hasRank(user, "MINISTRY_ADMIN");
}

export function canReview(user: User | null | undefined) {
  return hasRank(user, "FIELD_CONTRIBUTOR") || !!user?.canReview;
}

export function canEditOthersRecord(user: User | null | undefined, creatorRole: string | null | undefined): boolean {
  if (!hasRank(user, "PROFESSOR")) return false;
  if (isMasterAdmin(user)) return true;
  const role = creatorRole || "RESEARCHER";
  const creatorRank = Object.prototype.hasOwnProperty.call(ROLE_RANK, role)
    ? ROLE_RANK[role as UserRole]
    : ROLE_RANK.RESEARCHER;
  return roleRank(user) > creatorRank;
}

export function anySignedIn(user: User | null | undefined) {
  return !!user;
}

export function canDownloadDataset(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR") || !!user?.canDownloadDataset;
}

export function canSeeDataTile(user: User | null | undefined) {
  return hasRank(user, "PROFESSOR") || user?.role === "RESEARCHER";
}

export function canCreateRecords(user: User | null | undefined) {
  return hasRank(user, "RESEARCHER");
}

export function canEditOwnOrAdmin(user: User | null | undefined, ownerId?: string | null) {
  return isContentAdmin(user) || (!!user?.id && !!ownerId && user.id === ownerId);
}

export function mayDeleteQuestionnaire(user: User | null | undefined, ownerId?: string | null) {
  return canEditOwnOrAdmin(user, ownerId);
}

export function canViewProvenance(user: User | null | undefined) {
  return isAdmin(user) || !!user?.canViewProvenance;
}

export function canManageSecrets(user: User | null | undefined) {
  return isMasterAdmin(user);
}

export type RouteGuard = {
  path: string;
  can: (user: User | null | undefined) => boolean;
  gate: string;
  title: string;
  message: string;
  ministry?: boolean;
};

const RECORD_CREATOR_GUARD = {
  can: canCreateRecords,
  gate: "require_record_creator",
  title: "Researcher access required",
  message:
    "Needs Researcher access or above. You can still answer interviews, upload media and comment."
} as const;

export const APPROVAL_AUTHORITY_REFUSAL =
  "Only the Ministry Admin and the master admin approve reports.";

export const MINISTRY_EXPORT_REFUSAL = "For Ministry Admins, admins and the master admin.";

export const ROUTE_GUARDS: RouteGuard[] = [
  {
    path: "/users",
    can: canManageUsers,
    gate: "require_professor",
    title: "Professor access required",
    message:
      "Needs Professor access or above."
  },
  {
    path: "/admin",
    can: isAdmin,
    gate: "require_admin",
    title: "Admin access required",
    message: "Admins only."
  },
  {
    path: "/admin/analytics",
    can: isAdmin,
    gate: "require_admin",
    title: "Admin access required",
    message:
      "Admins only. Your own workshops are on Design workshops."
  },
  {
    path: "/admin/designers",
    can: canManageDesignerRoster,
    gate: "require_designer_roster_manager",
    title: "Admin access required",
    message:
      "Admins only."
  },
  {
    path: "/admin/access",
    can: canManageAccessRoster,
    gate: "require_access_manager",
    title: "Admin access required",
    message:
      "Admins only."
  },
  {
    path: "/ministry-dashboard",
    can: canSeeMinistryDashboard,
    gate: "require_ministry_dashboard_reader",
    ministry: true,
    title: "Ministry access required",
    message:
      "For Assistant Directors, Regional Directors, Ministry Admins and the master admin. Admins: use Cross-workshop analytics. Designers: use Design workshops."
  },
  {
    path: "/ministry-exports",
    can: canManageMinistryExports,
    gate: "require_ministry_export_manager (OVERSIGHT_ASSIGNER_ROLES, app/api/routes/ministry_exports.py)",
    ministry: true,
    title: "Ministry Admin access required",
    message: MINISTRY_EXPORT_REFUSAL
  },
  {
    path: "/annual-plan",
    can: canReadAnnualPlan,
    gate: "require_annual_plan_reader",
    ministry: true,
    title: "Ministry administrator access required",
    message:
      "For the Ministry Admin and above, and Regional Directors for their states."
  },
  {
    path: "/settings/api-keys",
    can: isAdmin,
    gate: "require_admin",
    title: "Admin access required",
    message:
      "Admins only. Key values are master admin only."
  },
  {
    path: "/settings/usage",
    can: isAdmin,
    gate: "require_usage_reader",
    title: "Admin access required",
    message:
      "Admins only."
  },
  {
    path: "/settings/tasks",
    can: canAssignTasks,
    gate: "require_admin",
    title: "Admin access required",
    message:
      "Admins only. Your own tasks are on Tasks."
  },
  {
    path: "/review",
    can: canReview,
    gate: "require_reviewer",
    title: "Review access required",
    message:
      "Needs Field Contributor access or above, or review access."
  },
  {
    path: "/data",
    can: canDownloadDataset,
    gate: "require_dataset_downloader",
    title: "Dataset access required",
    message:
      "Needs Professor access or dataset access. Use Search instead."
  },
  {
    path: "/data/go",
    can: anySignedIn,
    gate: "get_current_user + the record's own visibility (data_report go routes)",
    title: "Access required",
    message:
      "You need access to this record. Ask its owner or an admin."
  },
  {
    path: "/design-workshops/:id/provenance",
    can: isAdmin,
    gate: "require_admin (GET /design-workshops/{id}/provenance)",
    title: "Admin access required",
    message:
      "Admins only. Each stage still shows who filled in each field."
  },
  {
    path: "/design-review",
    can: canRunDesignWorkshops,
    gate: "can_run_design_workshops (load_ratable_workshop_or_404)",
    title: "Designer access required",
    message:
      "For designers, ministry officers and admins."
  },
  {
    path: "/sketches-and-prototypes",
    can: canRunDesignWorkshops,
    gate: "can_run_design_workshops (load_workshop_or_404 once a workshop is chosen; the picker's list is get_current_user + visible_to_clause)",
    title: "Designer access required",
    message:
      "For designers, ministry officers and admins."
  },
  {
    path: "/design-workshop-inspections",
    can: canInspectDesignWorkshops,
    gate: "assert_inspection_surface (INSPECTION_HOLDER_ROLES, services/design_workshop_inspectors.py)",
    title: "Inspector / Reviewer access required",
    message:
      "For Inspector / Reviewers, Ministry Admins and admins."
  },
  {
    path: "/officers",
    can: canAssignWorkshopOversight,
    gate: "assert_may_assign_oversight (OVERSIGHT_ASSIGNER_ROLES, services/design_workshop_oversight.py)",
    ministry: true,
    title: "Ministry Admin access required",
    message:
      "For Ministry Admins and admins. Directors see theirs on Workshops I monitor."
  },
  {
    path: "/officers/monitored",
    can: canReadWorkshopOversight,
    gate: "assert_oversight_surface (OVERSIGHT_HOLDER_ROLES, services/design_workshop_oversight.py)",
    ministry: true,
    title: "Officer access required",
    message:
      "For Assistant and Regional Directors, Ministry Admins and admins."
  },
  {
    path: "/design-workshop-approvals",
    can: canApproveDesignWorkshops,
    gate: "require_approving_authority (APPROVAL_AUTHORITY_ROLES, core/deps.py)",
    ministry: true,
    title: "Ministry Admin access required",
    message: APPROVAL_AUTHORITY_REFUSAL
  },
  {
    path: "/sanction-orders",
    can: (user) => hasRank(user, "ASSISTANT_DIRECTOR"),
    gate: "require_sanction_recorder",
    ministry: true,
    title: "Ministry officer access required",
    message:
      "Only an Assistant Director or above records sanction orders. Ask the officer holding yours — the workshop appears in your list once they do."
  },
  {
    path: "/design-workshops",
    can: canRunDesignWorkshops,
    gate: "can_run_design_workshops",
    title: "Designer access required",
    message:
      "For designers, ministry officers and admins."
  },
  {
    path: "/questionnaires",
    can: canRunDesignWorkshops,
    gate: "can_run_design_workshops (_require_designer)",
    title: "Designer access required",
    message:
      "For designers, ministry officers and admins. The shared questionnaire is on Take interview."
  },
  {
    path: "/designers/profile",
    can: canRunDesignWorkshops,
    gate: "require_designer",
    title: "Designer access required",
    message:
      "For designers, ministry officers and admins."
  },
  { path: "/artisans/new", ...RECORD_CREATOR_GUARD },
  { path: "/products/new", ...RECORD_CREATOR_GUARD },
  { path: "/tools/new", ...RECORD_CREATOR_GUARD }
];

function routeMatches(rulePath: string, pathname: string): boolean {
  const rule = rulePath.split("/").filter(Boolean);
  const actual = pathname.split("/").filter(Boolean);
  if (actual.length < rule.length) return false;
  return rule.every((segment, index) => segment.startsWith(":") || segment === actual[index]);
}

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

export function ministrySurface(pathname: string): boolean {
  return routeGuardFor(pathname)?.ministry === true;
}

export type RouteRedirect = {
  path: string;
  can: (user: User | null | undefined) => boolean;
  to: string;
};

export const ROUTE_REDIRECTS: RouteRedirect[] = [
  {
    path: "/workshop-access/manage",
    can: isAdmin,
    to: "/workshop-access/request"
  }
];

export function routeRedirectFor(user: User | null | undefined, pathname: string): string | null {
  let best: RouteRedirect | null = null;
  for (const rule of ROUTE_REDIRECTS) {
    if (!routeMatches(rule.path, pathname)) continue;
    if (!best || rule.path.length > best.path.length) best = rule;
  }
  return best && !best.can(user) ? best.to : null;
}

export const DESIGN_WORKSHOP_ROLES: readonly UserRole[] = [
  "DESIGNER",
  "MINISTRY_ADMIN",
  "REGIONAL_DIRECTOR",
  "ASSISTANT_DIRECTOR",
  "ADMIN",
  "MASTER_ADMIN"
];

export function canRunDesignWorkshops(user: User | null | undefined) {
  return !!user && DESIGN_WORKSHOP_ROLES.includes(user.role);
}

export const DESIGN_WORKSHOP_CREATOR_ROLES: readonly UserRole[] = ["MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"];

export function canCreateDesignWorkshops(user: User | null | undefined) {
  return !!user && DESIGN_WORKSHOP_CREATOR_ROLES.includes(user.role);
}

export const DESIGN_WORKSHOP_DATA_VIEW_ROLES: readonly UserRole[] = [
  "PROFESSOR",
  "ASSISTANT_DIRECTOR",
  "REGIONAL_DIRECTOR",
  "MINISTRY_ADMIN",
  "ADMIN",
  "MASTER_ADMIN"
];

export function canViewDesignWorkshopData(user: User | null | undefined) {
  return !!user && DESIGN_WORKSHOP_DATA_VIEW_ROLES.includes(user.role);
}

export const DESIGN_WORKSHOP_DATA_EXPORT_ROLES: readonly UserRole[] = ["ADMIN", "MASTER_ADMIN"];

export function canExportDesignWorkshopData(user: User | null | undefined) {
  return !!user && DESIGN_WORKSHOP_DATA_EXPORT_ROLES.includes(user.role);
}

export const DESIGN_WORKSHOP_CREATE_REFUSAL =
  "Only admins can start a workshop. Ask an admin to create it and add you — then you can fill in " +
  "all 22 stages and the report.";

export const INSPECTION_ROLES: readonly UserRole[] = ["INSPECTOR"];

export function isInspectorTier(user: User | null | undefined) {
  return !!user && INSPECTION_ROLES.includes(user.role);
}

export const INSPECTION_HOLDER_ROLES: readonly UserRole[] = [
  "INSPECTOR",
  "MINISTRY_ADMIN",
  "ADMIN",
  "MASTER_ADMIN"
];

export function canInspectDesignWorkshops(user: User | null | undefined) {
  return !!user && INSPECTION_HOLDER_ROLES.includes(user.role);
}

export const OVERSIGHT_ASSIGNER_ROLES: readonly UserRole[] = [
  "MINISTRY_ADMIN",
  "ADMIN",
  "MASTER_ADMIN"
];

export function canAssignWorkshopOversight(user: User | null | undefined) {
  return !!user && OVERSIGHT_ASSIGNER_ROLES.includes(user.role);
}

export const MINISTRY_EXPORT_ROLES: readonly UserRole[] = [
  "MINISTRY_ADMIN",
  "ADMIN",
  "MASTER_ADMIN"
];

export function canManageMinistryExports(user: User | null | undefined) {
  return !!user && MINISTRY_EXPORT_ROLES.includes(user.role);
}

export const OFFICER_ROLES: readonly UserRole[] = [
  "ASSISTANT_DIRECTOR",
  "REGIONAL_DIRECTOR",
  "MINISTRY_ADMIN"
];

export function isDirectorateTier(user: User | null | undefined) {
  return !!user && OFFICER_ROLES.includes(user.role);
}

export const ASSISTANT_DIRECTOR_HOLDER_ROLES: readonly UserRole[] = [
  "ASSISTANT_DIRECTOR",
  "MINISTRY_ADMIN",
  "ADMIN",
  "MASTER_ADMIN"
];

export const REGIONAL_DIRECTOR_HOLDER_ROLES: readonly UserRole[] = [
  "REGIONAL_DIRECTOR",
  "MINISTRY_ADMIN",
  "ADMIN",
  "MASTER_ADMIN"
];

export type OversightPost = "ASSISTANT_DIRECTOR" | "REGIONAL_DIRECTOR";

export const OVERSIGHT_POST_HOLDER_ROLES: Readonly<Record<OversightPost, readonly UserRole[]>> = {
  ASSISTANT_DIRECTOR: ASSISTANT_DIRECTOR_HOLDER_ROLES,
  REGIONAL_DIRECTOR: REGIONAL_DIRECTOR_HOLDER_ROLES
};

export function oversightPostsUserMayHold(user: User | null | undefined): OversightPost[] {
  if (!user) return [];
  return (Object.keys(OVERSIGHT_POST_HOLDER_ROLES) as OversightPost[]).filter((post) =>
    OVERSIGHT_POST_HOLDER_ROLES[post].includes(user.role)
  );
}

export function canReadWorkshopOversight(user: User | null | undefined) {
  return oversightPostsUserMayHold(user).length > 0;
}

export type WorkshopPost = OversightPost | "INSPECTION";

export function workshopPostsHeldBy(
  user: User | null | undefined,
  staffing: {
    oversight?: readonly { capacity: string; userId: string }[] | null;
    inspectors?: readonly { userId: string }[] | null;
  }
): WorkshopPost[] {
  const id = user?.id;
  if (!id) return [];
  const held: WorkshopPost[] = (Object.keys(OVERSIGHT_POST_HOLDER_ROLES) as OversightPost[]).filter(
    (post) => (staffing.oversight ?? []).some((row) => row.capacity === post && row.userId === id)
  );
  if ((staffing.inspectors ?? []).some((row) => row.userId === id)) held.push("INSPECTION");
  return held;
}

export function heldPostReadPath(posts: readonly WorkshopPost[], workshopId: string): string | null {
  const id = encodeURIComponent(workshopId);
  if (posts.some((post) => post !== "INSPECTION")) return `/officers/monitored/${id}`;
  if (posts.includes("INSPECTION")) return `/design-workshop-inspections/${id}`;
  return null;
}

export function heldPostEditRefusal(posts: readonly WorkshopPost[]): string | null {
  if (!posts.length) return null;
  const spoken: readonly WorkshopPost[] = ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "INSPECTION"];
  const named = spoken
    .filter((post) => posts.includes(post))
    .map((post) => (post === "INSPECTION" ? "inspector" : ROLE_LABELS[post]));
  const labels =
    named.length === 1 ? named[0] : `${named.slice(0, -1).join(", ")} and ${named[named.length - 1]}`;
  const readOn = [
    posts.some((post) => post !== "INSPECTION") ? "Workshops I monitor" : null,
    posts.includes("INSPECTION") ? "Workshops to inspect" : null
  ]
    .filter(Boolean)
    .join(" or ");
  return (
    `You are this workshop's ${labels}, so you can read it but not change it: whoever inspects or ` +
    "supervises a workshop does not write it. Ask whoever made the appointment to take you off that " +
    `post if you need to work on it. Every stage of it is open to you to read on ${readOn}.`
  );
}

export function selfReleaseRefusal(post: WorkshopPost): string {
  const label = post === "INSPECTION" ? "inspector" : ROLE_LABELS[post];
  return `You are this workshop's ${label}, and nobody takes themselves off a post: another administrator has to take you off.`;
}

export function selfReleaseRefused(post: WorkshopPost): string {
  return `${selfReleaseRefusal(post)} Nothing was changed.`;
}

export function canManageDesignerRoster(user: User | null | undefined) {
  return isAdmin(user);
}

export function canManageAccessRoster(user: User | null | undefined) {
  return isAdmin(user);
}

export function canManageAnnualPlan(user: User | null | undefined) {
  return hasRank(user, "MINISTRY_ADMIN");
}

export function canReadAnnualPlan(user: User | null | undefined) {
  return canManageAnnualPlan(user) || user?.role === "REGIONAL_DIRECTOR";
}

export const MINISTRY_DESK_ROLES: readonly UserRole[] = [
  "ASSISTANT_DIRECTOR",
  "REGIONAL_DIRECTOR",
  "MINISTRY_ADMIN",
  "MASTER_ADMIN"
];

export function canSeeMinistryDesk(user: User | null | undefined) {
  return !!user && MINISTRY_DESK_ROLES.includes(user.role);
}

export const MINISTRY_DASHBOARD_ROLES: readonly UserRole[] = [
  "ASSISTANT_DIRECTOR",
  "REGIONAL_DIRECTOR",
  "MINISTRY_ADMIN",
  "MASTER_ADMIN"
];

export function canSeeMinistryDashboard(user: User | null | undefined) {
  return !!user && MINISTRY_DASHBOARD_ROLES.includes(user.role);
}

export const ANALYTICS_READ_ROLES: readonly UserRole[] = [
  "REGIONAL_DIRECTOR",
  "MINISTRY_ADMIN",
  "ADMIN",
  "MASTER_ADMIN"
];

export function canReadWorkshopAnalytics(user: User | null | undefined) {
  return !!user && ANALYTICS_READ_ROLES.includes(user.role);
}

export const APPROVAL_AUTHORITY_ROLES: readonly UserRole[] = [
  "MINISTRY_ADMIN",
  "MASTER_ADMIN"
];

export function canApproveDesignWorkshops(user: User | null | undefined) {
  return !!user && APPROVAL_AUTHORITY_ROLES.includes(user.role);
}
