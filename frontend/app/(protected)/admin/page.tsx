"use client";

import { BudgetMeterCard } from "@/components/transcript/BudgetMeterCard";
import Link from "next/link";
import { useEffect, useId, useState } from "react";
import {
  CopyCheck,
  Activity,
  AudioLines,
  BadgeCheck,
  CalendarRange,
  ChartNoAxesCombined,
  ClipboardCheck,
  ClipboardList,
  FileArchive,
  KeyRound,
  Lock,
  MessageSquare,
  Settings,
  ShieldCheck,
  ShieldUser,
  SlidersHorizontal,
  Tags,
  Undo2,
  UserCog,
  Wrench,
  type LucideIcon
} from "lucide-react";

import { DeletedWorkshopsCard } from "@/components/admin/DeletedWorkshopsCard";
import {
  HELD_POST_PENDING_FILES,
  HeldPostNotice,
  MEDIA_HELD_LABEL,
  mediaListNotice,
  mediaWorkshopIds,
  useHeldPostRefusals,
  writesHeld
} from "@/components/designworkshop/HeldPostNotice";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { useAuth } from "@/components/AuthProvider";
import { usePendingAccessCount } from "@/components/hooks/usePendingAccessCount";
import { apiFetch } from "@/lib/api";
import { listPairs } from "@/lib/duplicates";
import { bytes, formatDateTime } from "@/lib/format";
import { ministryExportsEnabled } from "@/lib/ministryExports";
import {
  canManageAccessRoster,
  canManageAnnualPlan,
  canManageDesignerRoster,
  canManageMinistryExports,
  isAdmin,
  isMasterAdmin
} from "@/lib/permissions";
import type { MediaFile } from "@/lib/types";

type Tile = {
  label: string;
  description: string;
  href: string;
  icon: LucideIcon;
  visible?: boolean;
  badge?: number | null;
};

const PAGE_SIZE = 20;

export default function AdminHubPage() {
  const { user, loading: authLoading } = useAuth();
  const permitted = isAdmin(user);

  const [orphans, setOrphans] = useState<MediaFile[] | null>(null);
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);

  const pendingAccess = usePendingAccessCount(permitted && canManageAccessRoster(user));

  const [openDuplicates, setOpenDuplicates] = useState<number | null>(null);
  useEffect(() => {
    if (authLoading || !permitted) return;
    let cancelled = false;
    listPairs({ status: "OPEN", pageSize: 1 })
      .then((page) => {
        if (!cancelled) setOpenDuplicates(page.total);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [authLoading, permitted]);

  const heldFor = useHeldPostRefusals((orphans ?? []).flatMap(mediaWorkshopIds));
  const heldNoticeId = useId();

  useEffect(() => {
    if (authLoading || !permitted) return;
    apiFetch<MediaFile[]>("/media/orphans")
      .then(setOrphans)
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load recovered recordings"));
  }, [authLoading, permitted]);

  const header = (
    <PageHeader
      title="Settings"
      description="Admin tools."
      icon={<SlidersHorizontal className="h-5 w-5" aria-hidden />}
    />
  );

  if (authLoading) {
    return (
      <>
        {header}
        <section className="panel p-6 text-sm text-ink-500">Checking access…</section>
      </>
    );
  }

  if (!permitted) {
    return (
      <>
        {header}
        <section className="panel px-6 py-12 text-center">
          <div className="mx-auto mb-3 grid h-11 w-11 place-items-center rounded-full bg-purple-100 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h2 className="font-display text-base font-semibold text-ink-900">Admin access required</h2>
          <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-ink-500">
            Admins only.
          </p>
        </section>
      </>
    );
  }

  const tiles: Tile[] = [
    {
      label: "Reviews & approvals",
      description: "Approve, reject, or send submissions back for revision.",
      href: "/review",
      icon: ClipboardCheck
    },
    {
      label: "Duplicate artisans",
      description: "Review likely duplicates and merge them.",
      href: "/admin/duplicates",
      icon: CopyCheck,
      badge: openDuplicates || null
    },
    {
      label: "Recovered recordings",
      description: "Media from deleted records.",
      href: "#recovered-recordings",
      icon: AudioLines
    },
    {
      label: "Deleted workshops",
      description: "Restore deleted workshops.",
      href: "#deleted-workshops",
      icon: Undo2
    },
    {
      label: "User feedback",
      description: "Read and answer feedback.",
      href: "/feedback",
      icon: MessageSquare
    },
    {
      label: "Assign tools to artisans",
      description: "Link tools to artisans.",
      href: "/tools?assign=1",
      icon: Wrench
    },
    {
      label: "Task assignment",
      description: "Assign and track documentation work.",
      href: "/settings/tasks",
      icon: ClipboardList
    },
    {
      label: "Workshop access",
      description: "Requests, rosters and access levels.",
      href: "/workshop-access/manage",
      icon: ShieldCheck
    },
    {
      label: "Cross-workshop analytics",
      description: "Compare outcomes across workshops.",
      href: "/admin/analytics",
      icon: ChartNoAxesCombined
    },
    {
      label: "Manage users",
      description: "Roles, promotions, capability grants, and account admin.",
      href: "/users",
      icon: UserCog
    },
    {
      label: "Who may sign in",
      description:
        "Who may sign in, and who is waiting.",
      href: "/admin/access",
      icon: ShieldUser,
      visible: canManageAccessRoster(user),
      badge: pendingAccess?.pending || null
    },
    {
      label: "Types of workshop",
      description:
        "The “Type of workshop” options.",
      href: "/admin/workshop-types",
      icon: Tags,
      visible: isAdmin(user)
    },
    {
      label: "Designer roster",
      description: "Empanel, suspend and restore designers.",
      href: "/admin/designers",
      icon: BadgeCheck,
      visible: canManageDesignerRoster(user)
    },
    {
      label: "Annual plan",
      description:
        "This year's planned workshops. Upload the sheet; open workshops from it.",
      href: "/annual-plan",
      icon: CalendarRange,
      visible: canManageAnnualPlan(user)
    },
    {
      label: "Ministry exports",
      description: "Monthly Excel and PDF packs per scheme, and who receives them.",
      href: "/ministry-exports",
      icon: FileArchive,
      visible: canManageMinistryExports(user) && ministryExportsEnabled(user)
    },
    {
      label: "API keys",
      description: "Rank transcription providers and manage keys.",
      href: "/settings/api-keys",
      icon: KeyRound,
      visible: isAdmin(user)
    },
    {
      label: "Usage",
      description: "Screen usage, speed and errors.",
      href: "/settings/usage",
      icon: Activity,
      visible: isAdmin(user)
    },
    {
      label: "App settings",
      description: "App-wide settings and app updates.",
      href: "/settings",
      icon: Settings,
      visible: isMasterAdmin(user)
    }
  ];

  const orphanPages = Math.max(1, Math.ceil((orphans?.length ?? 0) / PAGE_SIZE));
  const orphanPage = Math.min(page, orphanPages);
  const visibleOrphans = (orphans ?? []).slice((orphanPage - 1) * PAGE_SIZE, orphanPage * PAGE_SIZE);

  return (
    <>
      {header}
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}

      <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
        {tiles
          .filter((tile) => tile.visible !== false)
          .map((tile) => (
            <Link
              key={tile.label}
              href={tile.href}
              className="group flex flex-col gap-2 rounded-lg border border-line-200 bg-card p-4 shadow-sm transition hover:border-purple-300 hover:shadow-md"
            >
              <div className="flex items-start justify-between gap-2">
                <div className="grid h-10 w-10 place-items-center rounded-md bg-purple-800">
                  <tile.icon className="h-5 w-5 text-white" aria-hidden />
                </div>
                {tile.badge ? (
                  <span className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-semibold text-amber-800">
                    {tile.badge}
                    <span className="font-medium">waiting</span>
                    <span className="sr-only">for a decision</span>
                  </span>
                ) : null}
              </div>
              <div className="font-display text-base font-bold leading-snug text-ink-900">{tile.label}</div>
              <p className="text-xs leading-5 text-ink-500">{tile.description}</p>
            </Link>
          ))}
      </div>

      <div className="mt-6">
        <BudgetMeterCard />
      </div>

      <section id="recovered-recordings" className="panel mt-6 overflow-hidden">
        <div className="border-b border-line-200 px-4 py-3">
          <h2 className="font-display font-bold text-ink-900">Recovered recordings</h2>
          <p className="text-sm text-ink-500">
            Relink these from the Media page.
          </p>
          <HeldPostNotice
            refusal={mediaListNotice(visibleOrphans.map((item) => heldFor(mediaWorkshopIds(item))), "relinked")}
            id={heldNoticeId}
            className="mt-2"
            sayPending
            pendingSentence={HELD_POST_PENDING_FILES}
          />
        </div>
        {!orphans ? (
          <div className="p-4 text-sm text-ink-500">{error ? "Could not load the list." : "Loading…"}</div>
        ) : orphans.length === 0 ? (
          <div className="p-4">
            <EmptyState title="Nothing to recover" body="Every media file is linked to a live record." />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[860px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>File</ResizableTh>
                  <ResizableTh>Type</ResizableTh>
                  <ResizableTh>Size</ResizableTh>
                  <ResizableTh>Was linked to</ResizableTh>
                  <ResizableTh>Uploaded</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {visibleOrphans.map((item) => {
                  const hold = heldFor(mediaWorkshopIds(item));
                  return (
                    <tr key={item.id}>
                      <td className="px-4 py-3">
                        <div className="font-medium text-ink-900">{item.originalFilename}</div>
                        {item.caption ? <div className="max-w-xs truncate text-xs text-ink-500">{item.caption}</div> : null}
                      </td>
                      <td className="px-4 py-3 text-ink-700">{item.mediaType}</td>
                      <td className="px-4 py-3 text-ink-700">{bytes(item.sizeBytes)}</td>
                      <td className="px-4 py-3 capitalize text-ink-700">{item.linkedRecordType ?? "-"}</td>
                      <td className="px-4 py-3 text-ink-700">{formatDateTime(item.createdAt)}</td>
                      <td className="px-4 py-3 text-right">
                        <RowActions>
                          {item.url ? (
                            <a href={item.url} target="_blank" rel="noreferrer" className={rowAction("neutral")}>
                              Open
                            </a>
                          ) : null}
                          {writesHeld(hold) ? (
                            <button
                              type="button"
                              className={rowAction("edit")}
                              disabled
                              aria-describedby={typeof hold === "string" ? heldNoticeId : undefined}
                            >
                              Relink in Media
                            </button>
                          ) : (
                            <Link href="/media" className={rowAction("edit")}>
                              Relink in Media
                            </Link>
                          )}
                          {typeof hold === "string" ? (
                            <span className="self-center rounded-md bg-amber-100 px-2 py-1 text-xs font-medium text-amber-800">
                              {MEDIA_HELD_LABEL}
                            </span>
                          ) : null}
                        </RowActions>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {orphans && orphans.length > 0 ? (
          <Pagination page={orphanPage} pages={orphanPages} total={orphans.length} onPage={setPage} />
        ) : null}
      </section>

      <DeletedWorkshopsCard />
    </>
  );
}
