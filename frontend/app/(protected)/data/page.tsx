"use client";

import { Lock } from "lucide-react";
import Link from "next/link";
import { Suspense } from "react";

import { useAuth } from "@/components/AuthProvider";
import { DataSearchPanel } from "@/components/data/DataSearchPanel";
import { LIST_PAGE_CEILING } from "@/components/data/cappedList";
import { DataExplorer, ExplorerHeader, type ExplorerConfig } from "@/components/dataExplorer/DataExplorer";
import type { BrowseType } from "@/components/dataExplorer/BrowseByType";
import { TranscriptDataPanel } from "@/components/transcript/TranscriptDataPanel";
import { listResource } from "@/lib/api";
import type { BrowsePage, ReportResponse } from "@/lib/dataExplorer/contract";
import { canCreateRecords, canDownloadDataset, canExportDesignWorkshopData, canViewDesignWorkshopData } from "@/lib/permissions";
import { RECORD_VIEW_HREF } from "@/lib/recordView";
import { toolRowLabel } from "@/lib/toolArtisans";
import type { Artisan, Craft, MediaFile, PageResult, ProductDocumentation, QuestionnaireInterview, ToolDocumentation, User, Workshop } from "@/lib/types";
import { workshopDisplayTitle } from "@/lib/workshopTitle";

const DW_SHEET_GROUP = "designWorkshop";

type WithCreator = { createdBy?: { name?: string | null } | null };

type LegacyRow = { id: string; name: string; subtitle?: string | null; createdAt?: string | null };

function legacyPage<T>(result: PageResult<T>, map: (row: T) => LegacyRow): BrowsePage {
  const items = result.items.map(map).sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""));
  return {
    items,
    total: result.total,
    page: result.page,
    pageSize: LIST_PAGE_CEILING,
    pages: result.pages,
    truncated: result.total > result.page * LIST_PAGE_CEILING
  };
}

function creator(row: WithCreator): string | null {
  return row.createdBy?.name ?? null;
}

type BrowseDef = {
  label: string;
  editHref: (id: string) => string;
  viewHref: ((id: string) => string) | null;
  load: (page: number, q: string) => Promise<BrowsePage>;
};

const BROWSE_TYPES: Record<string, BrowseDef> = {
  artisan: {
    label: "Artisans",
    editHref: (id) => `/artisans/${id}/edit`,
    viewHref: RECORD_VIEW_HREF.artisan,
    load: async (page, q) =>
      legacyPage(await listResource<Artisan>("/artisans", { page, pageSize: LIST_PAGE_CEILING, search: q }), (x) => ({
        id: x.id,
        name: `${x.name}${x.place ? ` · ${x.place}` : ""}`,
        subtitle: creator(x),
        createdAt: x.createdAt
      }))
  },
  product: {
    label: "Products",
    editHref: (id) => `/products/${id}/edit`,
    viewHref: RECORD_VIEW_HREF.product,
    load: async (page, q) =>
      legacyPage(await listResource<ProductDocumentation>("/products", { page, pageSize: LIST_PAGE_CEILING, search: q }), (x) => ({
        id: x.id,
        name: `${x.productName} · ${x.artisanName}`,
        subtitle: creator(x),
        createdAt: x.createdAt
      }))
  },
  tool: {
    label: "Tools",
    editHref: (id) => `/tools/${id}/edit`,
    viewHref: RECORD_VIEW_HREF.tool,
    load: async (page, q) =>
      legacyPage(await listResource<ToolDocumentation>("/tools", { page, pageSize: LIST_PAGE_CEILING, search: q }), (x) => ({
        id: x.id,
        name: toolRowLabel(x),
        subtitle: creator(x),
        createdAt: x.createdAt
      }))
  },
  workshop: {
    label: "Workshops",
    editHref: (id) => `/workshops?edit=${id}`,
    viewHref: RECORD_VIEW_HREF.workshop,
    load: async (page, q) =>
      legacyPage(await listResource<Workshop>("/workshops", { page, pageSize: LIST_PAGE_CEILING, search: q }), (x) => ({
        id: x.id,
        name: workshopDisplayTitle(x),
        subtitle: creator(x),
        createdAt: x.createdAt
      }))
  },
  craft: {
    label: "Crafts",
    editHref: (id) => `/crafts?edit=${id}`,
    viewHref: RECORD_VIEW_HREF.craft,
    load: async (page, q) =>
      legacyPage(await listResource<Craft & WithCreator>("/crafts", { page, pageSize: LIST_PAGE_CEILING, search: q }), (x) => ({
        id: x.id,
        name: x.place ? `${x.name} · ${x.place}` : x.name,
        subtitle: creator(x),
        createdAt: x.createdAt
      }))
  },
  questionnaire: {
    label: "Questionnaire",
    editHref: (id) => `/questionnaire/sessions/${encodeURIComponent(id)}`,
    viewHref: RECORD_VIEW_HREF.interview,
    load: async (page, q) =>
      legacyPage(await listResource<QuestionnaireInterview>("/questionnaire/interviews", { page, pageSize: LIST_PAGE_CEILING, search: q }), (x) => ({
        id: x.id,
        name: x.title?.trim() || "Untitled interview",
        subtitle: creator(x),
        createdAt: x.createdAt
      }))
  },
  process: {
    label: "Processes",
    editHref: (id) => `/processes?edit=${id}`,
    viewHref: RECORD_VIEW_HREF.process,
    load: async (page, q) =>
      legacyPage(
        await listResource<{ id: string; name: string; createdAt?: string } & WithCreator>("/processes", { page, pageSize: LIST_PAGE_CEILING, search: q }),
        (x) => ({ id: x.id, name: x.name, subtitle: creator(x), createdAt: x.createdAt })
      )
  },
  media: {
    label: "Media",
    editHref: () => "/media",
    viewHref: RECORD_VIEW_HREF.media,
    load: async (page, q) =>
      legacyPage(await listResource<MediaFile>("/media", { page, pageSize: LIST_PAGE_CEILING, search: q }), (x) => ({
        id: x.id,
        name: [x.originalFilename?.trim() || "Media", x.mediaType].filter(Boolean).join(" · "),
        subtitle: x.uploadedBy?.name ?? null,
        createdAt: x.createdAt
      }))
  }
};

const BROWSE: BrowseType[] = Object.entries(BROWSE_TYPES).map(([value, def]) => ({
  value,
  label: def.label,
  viewHref: def.viewHref,
  updateHref: value === "media" ? null : def.editHref,
  legacy: (page, q) => def.load(page, q)
}));

function datasetLine(user: User | null): string | null {
  if (!canViewDesignWorkshopData(user)) return null;
  return canExportDesignWorkshopData(user) ? "Includes design workshop stages." : "Design workshop stages are in the By design workshop folder.";
}

function tablesNotice(report: ReportResponse): string | null {
  const blocked = report.designWorkshopsDownloadable === false && report.sheets.some((sheet) => sheet.group === DW_SHEET_GROUP);
  return blocked ? "Design workshop tables are on screen only. The .xlsx carries the rest." : null;
}

const TITLE = "Data";
const DESCRIPTION = "Browse, check and download everything you can see.";

const WIDE = "sm:max-lg:mx-1 lg:mx-[calc((100%-min(1360px,100vw-80px))/2)]";

const header = (
  <div className="mb-4">
    <ExplorerHeader title={TITLE} description={DESCRIPTION} />
  </div>
);

function recordHref(kind: string, id: string): string | null {
  const key = kind as keyof typeof RECORD_VIEW_HREF;
  return key in RECORD_VIEW_HREF ? RECORD_VIEW_HREF[key](id) : null;
}

function Explorer({ user }: { user: User }) {
  const config: ExplorerConfig = {
    title: TITLE,
    description: DESCRIPTION,
    rootName: "Dataset root",
    extra: {},
    downloadExtra: {},
    statusKey: "",
    viewer: user.id,
    fileHref: (mediaId) => RECORD_VIEW_HREF.media(mediaId),
    recordHref,
    renderSearch: (reveal, openPreview) => <DataSearchPanel onReveal={reveal} onPreview={openPreview} />,
    emptyBody: (folder) => `Files appear as soon as someone records for ${folder}.`,
    emptyAction: canCreateRecords(user) ? (
      <Link href="/media" className="inline-flex min-h-11 items-center rounded-md bg-purple-700 px-4 text-sm font-semibold text-white hover:bg-purple-800 dark:bg-purple-300 dark:text-purple-950">
        Record now
      </Link>
    ) : undefined,
    browseTypes: BROWSE,
    datasetLine: datasetLine(user),
    tablesNotice,
    secondary: <TranscriptDataPanel />
  };
  return <DataExplorer config={config} />;
}

export default function DataBrowserPage() {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <>
        {header}
        <section className="panel p-6 text-sm text-ink-500">Checking access…</section>
      </>
    );
  }

  if (!user || !canDownloadDataset(user)) {
    return (
      <>
        {header}
        <section className="panel px-6 py-12 text-center">
          <div className="mx-auto mb-3 grid h-11 w-11 place-items-center rounded-full bg-purple-100 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h2 className="font-display text-base font-semibold text-ink-900">Dataset access is restricted</h2>
          <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-ink-500">Needs Professor access or dataset access. Ask an admin.</p>
        </section>
      </>
    );
  }

  return (
    <div className={WIDE}>
      <Suspense fallback={<section className="panel p-6 text-sm text-ink-500">Loading…</section>}>
        <Explorer user={user} />
      </Suspense>
    </div>
  );
}
