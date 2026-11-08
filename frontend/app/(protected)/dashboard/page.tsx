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
import { OpenableRow, OpenableRowLink } from "@/components/data/OpenableRow";
import { EntryPointCard } from "@/components/dashboard/EntryPointCard";
import { MegaCard, type MegaTone } from "@/components/dashboard/MegaCard";
import { MinistryDeskCard } from "@/components/dashboard/MinistryDeskCard";
import { useMegaCards } from "@/components/dashboard/useMegaCards";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { StatusBadge } from "@/components/StatusBadge";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch } from "@/lib/api";
import { useCraftCatalogue } from "@/lib/craftCatalogue";
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
import { RECORD_VIEW_HREF } from "@/lib/recordView";

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
    createdByName?: string | null;
  }>;
  mine?: StatTotals;
};

type Tile = {
  label: string;
  icon: LucideIcon;
  newHref?: string;
  updateHref?: string;
  newLabel?: string;
  visible?: boolean;
  group?: TileGroup;
};

const TILE_GROUPS = [
  {
    id: "designers" as const,
    title: "For designers",
    note: "Design & prototype workshops, sketches and reviews.",
    tone: "purple" as MegaTone,
    icon: DraftingCompass
  },
  {
    id: "records" as const,
    title: "Records",
    note: "Artisans, products, processes and tools.",
    tone: "archive" as MegaTone,
    icon: Boxes
  },
  {
    id: "misc" as const,
    title: "Miscellaneous",
    note: "Browse, map, scan and share.",
    tone: "errand" as MegaTone,
    icon: Layers
  },
  {
    id: "admin" as const,
    title: "Admin",
    note: "Users, access and settings.",
    tone: "steward" as MegaTone,
    icon: Settings
  }
];

type TileGroup = (typeof TILE_GROUPS)[number]["id"];

function recordHref(type: string, id: string): string | null {
  switch ((type || "").toLowerCase()) {
    case "artisan":
      return RECORD_VIEW_HREF.artisan(id);
    case "product":
      return RECORD_VIEW_HREF.product(id);
    case "tool":
      return RECORD_VIEW_HREF.tool(id);
    case "process":
      return RECORD_VIEW_HREF.process(id);
    case "workshop":
      return RECORD_VIEW_HREF.workshop(id);
    case "craft":
      return RECORD_VIEW_HREF.craft(id);
    case "questionnaire":
    case "interview":
      return RECORD_VIEW_HREF.interview(id);
    default:
      return null;
  }
}

export default function DashboardPage() {
  return <DashboardView />;
}

function DashboardView() {
  const { user } = useAuth();
  const { adminMode } = useAdminView();
  const craftCatalogue = useCraftCatalogue();
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiFetch<DashboardStats>("/dashboard/stats")
      .then(setStats)
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load dashboard"));
  }, []);

  const megaCards = useMegaCards("dashboard");

  const creator = canCreateRecords(user);
  const adminSurface = (allowed: boolean) => allowed && (!isAdmin(user) || adminMode);

  const tiles: Tile[] = [
    {
      label: "Design workshop",
      icon: DraftingCompass,
      newHref: "/design-workshops?new=1",
      updateHref: "/design-workshops",
      visible: canRunDesignWorkshops(user),
      newLabel: "New workshop",
      group: "designers"
    },
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
    { label: "Questionnaire", icon: ClipboardList, newHref: "/questionnaire?new=1", updateHref: "/questionnaire", newLabel: "New interview", group: "records" },
    { label: "Miscellaneous Media", icon: Images, newHref: "/media", newLabel: "Upload", group: "records" },
    { label: "Scan a code", icon: QrCode, newHref: "/scan", newLabel: "Open", group: "misc" },
    {
      label: "Data",
      icon: Eye,
      newHref: canDownloadDataset(user) ? "/data" : "/search",
      newLabel: "Open",
      visible: canSeeDataTile(user),
      group: "misc"
    },
    { label: "Map", icon: MapPinned, newHref: "/map", newLabel: "Open", group: "misc" },
    {
      label: "Consolidated questionnaire",
      icon: Layers,
      newHref: "/questionnaire/coverage",
      newLabel: "Open",
      group: "misc"
    },
    { label: "Tasks", icon: ListTodo, newHref: "/tasks", newLabel: "Open", group: "misc" },
    { label: "Sharing", icon: Share2, newHref: "/sharing", group: "misc" },
    { label: "Workshop access", icon: LockOpen, newHref: "/workshop-access", newLabel: "Open", group: "misc" },
    {
      label: "My designer profile",
      icon: IdCard,
      newHref: "/designers/profile",
      newLabel: "Open",
      group: "designers",
      visible: canRunDesignWorkshops(user)
    },
    { label: "Users", icon: UserCog, newHref: "/users", visible: adminSurface(canManageUsers(user)), newLabel: "Manage", group: "admin" },
    { label: "Settings", icon: Settings, newHref: "/admin", visible: adminSurface(isAdmin(user)), newLabel: "Open", group: "admin" },
    { label: "Craft", icon: Brush, newHref: "/crafts?new=1", updateHref: "/crafts", visible: canManageCrafts(user), group: "records" },
    {
      label: "Workshop",
      icon: UsersRound,
      newHref: canCreateWorkshops(user) ? "/workshops?new=1" : undefined,
      updateHref: "/workshops",
      visible: canManageWorkshops(user),
      group: "records"
    }
  ];

  const statCards = stats
    ? [
        { label: "Artisans", value: stats.totalArtisans, icon: Users, href: "/search?type=artisans" },
        { label: "Workshops", value: stats.totalWorkshops, icon: MapPinned, href: "/search?type=workshops" },
        { label: "Crafts", value: craftCatalogue.catalogue?.crafts.length ?? "—", icon: Brush, href: "/crafts" },
        {
          label: "Craft categories",
          value: craftCatalogue.catalogue?.categories.length ?? "—",
          icon: Layers,
          href: "/crafts"
        },
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
      <PageHeader title="What would you like to do?" back={false} />
      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">{error}</div>
      ) : null}
      <MinistryDeskCard />
      <div className="relative">
        <div aria-hidden className="pointer-events-none absolute inset-x-0 -inset-y-8 overflow-hidden">
          <div className="absolute -left-12 -top-4 h-72 w-72 rounded-full bg-purple-300/25 blur-3xl" />
          <div className="absolute -right-8 bottom-0 h-80 w-80 rounded-full bg-purple-400/20 blur-3xl" />
        </div>
        <div className="relative grid items-start gap-6 lg:grid-cols-2">
          {(() => {
            const visible = tiles.filter((tile) => tile.visible !== false);
            const seen = new Set<Tile>();
            return TILE_GROUPS.map((group, index) => {
              const members = visible.filter((tile) => {
                const belongs =
                  tile.group === group.id ||
                  (group.id === "misc" && !TILE_GROUPS.some((candidate) => candidate.id === tile.group));
                if (belongs) seen.add(tile);
                return belongs;
              });
              const siblings = group.id === "designers" ? 1 : 0;
              if (members.length + siblings === 0) return null;
              return (
                <MegaCard
                  key={group.id}
                  title={group.title}
                  note={group.note}
                  icon={group.icon}
                  tone={group.tone}
                  count={members.length + siblings}
                  countLabel="destination"
                  expanded={megaCards.isOpen(group.id)}
                  onToggle={() => megaCards.toggle(group.id)}
                >
                  <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
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
                    {group.id === "designers" ? (
                      <EntryPointCard
                        label="My questionnaires"
                        description="Build your own interview form from the .xlsx pro-forma, and record answers against it — separate from the shared artisan questionnaire on Take interview."
                        icon={FileSpreadsheet}
                        href="/questionnaires"
                      />
                    ) : null}
                  </div>
                  {index === TILE_GROUPS.length - 1 && seen.size !== visible.length ? (
                    <p className="mt-3 text-xs leading-5 text-ink-500">
                      {visible.length - seen.size} more are in the menu.
                    </p>
                  ) : null}
                </MegaCard>
              );
            });
          })()}
        </div>
      </div>

      {!creator ? (
        <p className="mt-4 rounded-md border border-line-200 bg-surface-50 px-4 py-3 text-sm leading-6 text-ink-500">
          You are signed in as <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>. You can answer interviews, upload media and comment — find entries in{" "}
          <Link href="/search" className="font-medium text-purple-700 dark:text-purple-300 underline-offset-2 hover:underline">
            Search
          </Link>
          . Creating records needs Researcher access.{" "}
          <Link href="/guide" className="font-medium text-purple-700 dark:text-purple-300 underline-offset-2 hover:underline">
            Open the walkthrough
          </Link>
          .
        </p>
      ) : null}

      <section className="mt-8">
        <div className="mb-3 flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
          <h2 className="font-display text-lg font-bold text-ink-900">At a glance</h2>
          <p className="text-xs text-ink-500">Across all records, not only your own entries.</p>
        </div>
        {!stats && !error ? (
          <div className="panel p-4 text-sm text-ink-500">Loading...</div>
        ) : (
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
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
                  className="panel block p-4 transition-shadow hover:shadow-md focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-purple-700"
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

      {mineCards ? (
        <section className="mt-6">
          <div className="mb-3 flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
            <h2 className="font-display text-lg font-bold text-ink-900">Your contribution</h2>
            <Link
              href="/activity"
              className="text-xs font-semibold text-purple-700 dark:text-purple-300 underline-offset-2 hover:underline"
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
                className="panel block p-3 transition-shadow hover:shadow-md focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-purple-700"
              >
                <div className="flex items-center gap-2">
                  <card.icon className="h-4 w-4 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden />
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
            The newest entries across all records, whoever filed them.
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
                  <th className="px-4 py-3">Recorded by</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3">Created</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {stats.recentSubmissions.map((item) => {
                  const href = recordHref(item.type, item.id);
                  const cells = (
                    <>
                    <td className="px-4 py-3 font-medium text-ink-900">
                      {href ? <OpenableRowLink href={href} className="text-purple-700 dark:text-purple-300">{item.title}</OpenableRowLink> : item.title}
                    </td>
                    <td className="px-4 py-3 capitalize text-ink-700">{item.type}</td>
                    <td className="px-4 py-3 text-ink-700">{item.place ?? "-"}</td>
                    <td className="px-4 py-3 text-ink-700">{item.createdByName || "-"}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={item.status} />
                    </td>
                    <td className="px-4 py-3 text-ink-700">{formatDateTime(item.createdAt)}</td>
                    </>
                  );
                  return href ? (
                    <OpenableRow key={`${item.type}-${item.id}`} href={href}>
                      {cells}
                    </OpenableRow>
                  ) : (
                    <tr key={`${item.type}-${item.id}`}>{cells}</tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
