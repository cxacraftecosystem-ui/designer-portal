"use client";

import { Toggle } from "@/components/ui/Toggle";
import { ProgressBar } from "@/components/ui/ProgressBar";
import { use, useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { CloudOff, Download, FileText } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import { heldPostReason, useHeldPostRefusal } from "@/components/designworkshop/HeldPostNotice";
import { countCodeSpans, useReportMediaUrls } from "@/components/designworkshop/report/ReportBlock";
import { ReportAccentPicker, resolvePreviewPalette } from "@/components/designworkshop/report/ReportAccentPicker";
import { ReportSheets } from "@/components/designworkshop/report/ReportSheet";
import type { PreviewBlock, PreviewMeta } from "@/components/designworkshop/report/previewModel";
import { useStableBlocks } from "@/components/designworkshop/report/StageDocumentPreview";
import {
  REPORT_STAGE_KEY,
  ReportSettingsPanel,
  settingText,
  TranscriptAnnexurePanel,
  wantsTranscripts
} from "@/components/designworkshop/report/ReportSettingsPanel";
import {
  downloadDesignWorkshopReport,
  fetchReportSources,
  fetchStageRegistry,
  getDesignWorkshop,
  listDesignWorkshopTranscripts,
  listReportTemplates,
  overallPercent,
  previewDesignWorkshopReport,
  saveBlobToDisk,
  saveDesignWorkshopStage,
  type DwDetail,
  type DwEntryData,
  type DwPreview,
  type DwStageCompleteness,
  type DwRegistry,
  type DwTemplate,
  type DwTranscriptList
} from "@/lib/designWorkshops";
import { listDesignWorkshopAiLayers } from "@/lib/aiLayers";
import { loadDraft, loadRegistry, localCompleteness } from "@/lib/designWorkshopStore";
import { ACCENT_PRESETS } from "@/lib/reportTheme";
import { ApiError } from "@/lib/api";
import { useAuth } from "@/components/AuthProvider";
import type { ReportOptions } from "@/lib/offlineReport/assemble";
import { buildPreviewOnDevice } from "@/lib/offlineReport/devicePreview";
import type { Registry } from "@/lib/offlineReport/builder";
import { browserWriters } from "@/lib/offlineReport/browserWriters";
import { draftReportInput, generateOfflineFile } from "@/lib/offlineReport/generate";
import { warmReportAssets } from "@/lib/offlineReport/offlineAssets";
import { getSources, putSources } from "@/lib/offlineReport/reportCache";
import { TEMPLATES } from "@/lib/offlineReport/templates";
import { isUnreachable } from "@/lib/offline";
import { reportServerId, reportTemplateId } from "./reportTarget";
import { ActionGroup } from "@/components/ui/ActionGroup";

type TranscriptOverride = "" | "YES" | "NO";

const TRANSCRIPT_OPTIONS = [
  { value: "", label: "As saved on stage 20" },
  { value: "YES", label: "Include the transcripts" },
  { value: "NO", label: "Leave the transcripts out" }
];

function runningFurniture(detail: DwDetail | null, settings: DwEntryData, templateName: string) {
  const derivedHead = [detail?.craftName, detail?.clusterName].filter(Boolean).join(" — ");
  const derivedFoot = `${templateName || detail?.templateId || ""} · ${detail?.workshopCode || detail?.id || ""}`.trim();
  return {
    headerText: settingText(settings, "headerText") || derivedHead,
    footerText: settingText(settings, "footerText") || derivedFoot
  };
}

const EMPTY_SETTINGS: DwEntryData = {};

export default function DesignWorkshopReportPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);

  const [remoteId, setRemoteId] = useState<string | null>(null);
  const [localOnly, setLocalOnly] = useState(false);
  const postRefusal = useHeldPostRefusal(remoteId);

  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [detail, setDetail] = useState<DwDetail | null>(null);
  const [templates, setTemplates] = useState<DwTemplate[]>([]);
  const [templateId, setTemplateId] = useState<string>("");
  const [templateTouched, setTemplateTouched] = useState(false);
  const [settings, setSettings] = useState<DwEntryData>(EMPTY_SETTINGS);
  const [settingsDraft, setSettingsDraft] = useState<DwEntryData | null>(null);
  const drawnSettings = settingsDraft ?? settings;
  const [preview, setPreview] = useState<DwPreview | null>(null);
  const [previewing, setPreviewing] = useState(true);
  const [previewFailed, setPreviewFailed] = useState(false);
  const [retryToken, setRetryToken] = useState(0);
  const [transcripts, setTranscripts] = useState<DwTranscriptList | null>(null);
  const [transcriptOverride, setTranscriptOverride] = useState<TranscriptOverride>("");
  const [includeAiLayers, setIncludeAiLayers] = useState(false);
  const [acceptedLayerCount, setAcceptedLayerCount] = useState<number | null>(null);
  const [acceptedLayersUnknown, setAcceptedLayersUnknown] = useState(false);
  const [accentOverride, setAccentOverride] = useState("");
  const [savingAccent, setSavingAccent] = useState(false);
  const [downloading, setDownloading] = useState<string | null>(null);
  const [downloadWarnings, setDownloadWarnings] = useState<string[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [online, setOnline] = useState(true);
  const [unsentStages, setUnsentStages] = useState(0);
  const [stage20Pending, setStage20Pending] = useState(false);
  const previewGeneration = useRef(0);
  const { user } = useAuth();
  const userId = user?.id ?? null;
  const [builtHere, setBuiltHere] = useState(false);
  const [fileBuiltHere, setFileBuiltHere] = useState(false);
  const [deviceScores, setDeviceScores] = useState<Record<string, DwStageCompleteness> | null>(null);

  const buildHere = useCallback(
    (template: string): Promise<DwPreview | null> => buildPreviewOnDevice(id, userId, template),
    [id, userId]
  );

  const adoptDraftForReport = useCallback(async () => {
    setTemplates((current) =>
      current.length ? current : TEMPLATES.map((t) => ({ id: t.id, name: t.name, description: t.description }))
    );
    const draft = await loadDraft(id);
    if (!draft) {
      setPreviewing(false);
      return;
    }
    try {
      const { registry: loaded } = await loadRegistry();
      setRegistry((current) => current ?? loaded);
      setDeviceScores(localCompleteness(loaded, draft));
    } catch {
    }
    const stage20 = draft.stages[REPORT_STAGE_KEY];
    const nextSettings: DwEntryData = stage20 ? Object.assign({}, ...Object.values(stage20.singletons)) : EMPTY_SETTINGS;
    setSettings((current) => (current === EMPTY_SETTINGS ? nextSettings : current));
    const seeded = reportTemplateId(settingText(nextSettings, "templateId"), draft.header.templateId);
    setTemplateId((current) => current || seeded);
    if (!seeded) setPreviewing(false);
  }, [id]);

  useEffect(() => {
    const read = () => setOnline(typeof navigator === "undefined" || navigator.onLine !== false);
    read();
    window.addEventListener("online", read);
    window.addEventListener("offline", read);
    return () => {
      window.removeEventListener("online", read);
      window.removeEventListener("offline", read);
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    void loadDraft(id).then((draft) => {
      if (cancelled) return;
      const target = reportServerId(id, draft);
      setRemoteId(target);
      setLocalOnly(target === null);
      if (target === null) void adoptDraftForReport();
      if (!draft) return;
      setUnsentStages(
        Object.values(draft.stages).filter((stage) => stage.dirtyAt !== null || stage.removedFrom.length > 0).length
      );
      const stage = draft.stages[REPORT_STAGE_KEY];
      setStage20Pending(Boolean(stage && (stage.dirtyAt !== null || stage.removedFrom.length > 0)));
    });
    return () => {
      cancelled = true;
    };
  }, [id, adoptDraftForReport]);

  useEffect(() => {
    if (!remoteId) return;
    let cancelled = false;
    (async () => {
      try {
        const [nextRegistry, nextDetail, nextTemplates] = await Promise.all([
          fetchStageRegistry(),
          getDesignWorkshop(remoteId),
          listReportTemplates()
        ]);
        if (cancelled) return;
        setError(null);
        setRegistry(nextRegistry);
        setDetail(nextDetail);
        setTemplates(nextTemplates);
        const nextSettings = nextDetail.stages?.[REPORT_STAGE_KEY]?.singleton ?? EMPTY_SETTINGS;
        setSettings(nextSettings);
        const seeded = reportTemplateId(settingText(nextSettings, "templateId"), nextDetail.templateId);
        setTemplateId(seeded);
        if (!seeded) setPreviewing(false);
      } catch (err) {
        if (cancelled) return;
        if (isUnreachable(err)) {
          void adoptDraftForReport();
          return;
        }
        setError(err instanceof Error ? err.message : "Unable to load this design workshop");
        setPreviewing(false);
        setPreviewFailed(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [remoteId, retryToken, adoptDraftForReport]);

  useEffect(() => {
    if (!remoteId || !online || !userId) return;
    void fetchReportSources(remoteId)
      .then((sources) => putSources(remoteId, userId, sources))
      .catch(() => {});
    void warmReportAssets();
  }, [remoteId, online, userId]);

  const loadPreview = useCallback(
    async (template: string) => {
      const mine = ++previewGeneration.current;
      setPreviewing(true);
      setPreviewFailed(false);
      const fromDevice = async (): Promise<boolean> => {
        const built = await buildHere(template).catch(() => null);
        if (mine !== previewGeneration.current) return true;
        if (!built) return false;
        setPreview(built);
        setBuiltHere(true);
        setPreviewFailed(false);
        setError(null);
        return true;
      };
      try {
        if (!remoteId || !online) {
          if (!(await fromDevice()) && mine === previewGeneration.current) setPreviewFailed(true);
          return;
        }
        try {
          const next = await previewDesignWorkshopReport(remoteId, template || undefined);
          if (mine !== previewGeneration.current) return;
          setPreview(next);
          setBuiltHere(false);
          setPreviewFailed(false);
          setError(null);
        } catch (err) {
          if (mine !== previewGeneration.current) return;
          if (isUnreachable(err) && (await fromDevice())) return;
          if (mine !== previewGeneration.current) return;
          setPreviewFailed(true);
          setError(err instanceof Error ? err.message : "Unable to build the report preview");
        }
      } finally {
        if (mine === previewGeneration.current) setPreviewing(false);
      }
    },
    [remoteId, online, buildHere]
  );

  useEffect(() => {
    if (!templateId) return;
    loadPreview(templateTouched ? templateId : "");
  }, [templateId, templateTouched, loadPreview]);

  const retryPreview = useCallback(() => {
    if (templateId) {
      void loadPreview(templateTouched ? templateId : "");
      return;
    }
    setPreviewFailed(false);
    setPreviewing(true);
    setRetryToken((token) => token + 1);
  }, [templateId, templateTouched, loadPreview]);

  const refreshWorkshop = useCallback(async (): Promise<boolean> => {
    if (!remoteId) return false;
    try {
      const next = await getDesignWorkshop(remoteId);
      setDetail(next);
      const nextSettings = next.stages?.[REPORT_STAGE_KEY]?.singleton ?? EMPTY_SETTINGS;
      setSettings(nextSettings);
      if (!templateTouched) setTemplateId(reportTemplateId(settingText(nextSettings, "templateId"), next.templateId));
      return true;
    } catch {
      return false;
    }
  }, [remoteId, templateTouched]);

  const annexureWanted = transcriptOverride === "YES" || (transcriptOverride === "" && wantsTranscripts(settings));

  useEffect(() => {
    if (!remoteId || !annexureWanted) return;
    let cancelled = false;
    void listDesignWorkshopTranscripts(remoteId)
      .then((next) => {
        if (!cancelled) setTranscripts(next);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [remoteId, annexureWanted]);

  useEffect(() => {
    if (!remoteId || !includeAiLayers) return;
    let cancelled = false;
    setAcceptedLayersUnknown(false);
    void listDesignWorkshopAiLayers(remoteId)
      .then((next) => {
        if (cancelled) return;
        setAcceptedLayerCount(next.accepted);
      })
      .catch(async () => {
        const kept = await getSources(remoteId, userId);
        if (cancelled) return;
        if (kept) {
          setAcceptedLayerCount(kept.sources.aiLayers.filter((layer) => layer.accepted).length);
          return;
        }
        setAcceptedLayerCount(null);
        setAcceptedLayersUnknown(true);
      });
    return () => {
      cancelled = true;
    };
  }, [remoteId, includeAiLayers, userId]);

  const templateName = templates.find((template) => template.id === templateId)?.name ?? preview?.meta.templateName ?? "";
  const { headerText, footerText } = useMemo(
    () => runningFurniture(detail, drawnSettings, templateName),
    [detail, drawnSettings, templateName]
  );

  const pageSize = settingText(drawnSettings, "pageSize") || preview?.meta.pageSize || "A4";

  const savedAccent = useMemo(() => {
    const hex = settingText(settings, "themeAccent");
    if (hex) return hex;
    const preset = settingText(settings, "themePreset");
    return ACCENT_PRESETS.find((option) => option.key === preset)?.hex ?? "";
  }, [settings]);

  const {
    palette,
    accent: previewAccent,
    chosen: accentChosen
  } = useMemo(() => resolvePreviewPalette(accentOverride, savedAccent), [accentOverride, savedAccent]);

  const blocks: PreviewBlock[] = useStableBlocks(useMemo(() => (preview?.blocks ?? []) as PreviewBlock[], [preview]));
  const mediaUrls = useReportMediaUrls(blocks);
  const codeSpans = useMemo(() => countCodeSpans(blocks), [blocks]);

  const fileOptions: ReportOptions = {
    templateId: templateTouched ? templateId || null : null,
    pageSize: settingText(settings, "pageSize") || null,
    headerText: settingText(settings, "headerText") || null,
    footerText: settingText(settings, "footerText") || null,
    themeAccent: accentOverride || null,
    includeTranscripts: transcriptOverride === "" ? null : transcriptOverride === "YES",
    includeAiLayers
  };

  async function writeHere(format: "DOCX" | "PDF") {
    const draft = await loadDraft(id);
    if (!draft) throw new Error("This workshop isn't on this device.");
    const registry = (await loadRegistry()).registry as unknown as Registry;
    const sources = draft.remoteId ? ((await getSources(draft.remoteId, userId))?.sources ?? null) : null;
    const file = await generateOfflineFile(
      registry,
      draftReportInput(draft, sources),
      format,
      fileOptions,
      browserWriters(userId, palette)
    );
    saveBlobToDisk(file.blob, file.fileName);
    setFileBuiltHere(true);
    setDownloadWarnings(file.warnings);
  }

  async function download(format: "DOCX" | "PDF") {
    setDownloading(format);
    setError(null);
    setDownloadWarnings(null);
    try {
      if (!remoteId || !online) {
        await writeHere(format);
        return;
      }
      try {
        const file = await downloadDesignWorkshopReport(remoteId, {
          templateId: fileOptions.templateId ?? null,
          formats: [format],
          pageSize: fileOptions.pageSize || undefined,
          headerText: fileOptions.headerText || undefined,
          footerText: fileOptions.footerText || undefined,
          themeAccent: fileOptions.themeAccent || undefined,
          includeTranscripts: fileOptions.includeTranscripts ?? undefined,
          includeAiLayers
        });
        saveBlobToDisk(file.blob, file.fileName);
        setFileBuiltHere(false);
        setDownloadWarnings(file.warnings);
      } catch (err) {
        if (err instanceof ApiError) {
          setError(
            `Couldn't generate the ${format}: ${err.message} Your workshop is unaffected.`
          );
          return;
        }
        await writeHere(format);
      }
    } catch (err) {
      setError(
        `Couldn't write the ${format}: ${err instanceof Error ? err.message : "something went wrong"} ` +
          "Your workshop is unaffected."
      );
    } finally {
      setDownloading(null);
    }
  }

  const completeness = detail?.completeness ?? deviceScores ?? undefined;
  const incomplete = useMemo(() => {
    if (!registry || !completeness) return [];
    return registry.stages
      .map((stage) => ({ stage, score: completeness[stage.key] }))
      .filter((entry) => entry.score && !entry.score.isComplete);
  }, [registry, completeness]);

  const percent = overallPercent(completeness);

  return (
    <>
      <PageHeader
        title="Report"
        description={
          detail
            ? `Generated from “${detail.title}” — the same document the .docx and .pdf are written from.`
            : localOnly || builtHere
              ? "Built on this device from everything saved on it."
              : previewFailed
                ? "This workshop couldn't be loaded. Use Try again at the foot of the page."
                : "Loading…"
        }
        icon={<FileText className="h-5 w-5" aria-hidden />}
        actions={
          <Link href={`/design-workshops/${id}`} className="field-button-secondary">
            All stages
          </Link>
        }
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}

      <section className="panel mb-5 grid gap-4 p-4">
        <div className="grid gap-3 md:grid-cols-[minmax(0,22rem)_1fr] md:items-end">
          <FieldBlock label="Report template">
            <Dropdown
              value={templateId}
              onChange={(value) => {
                setTemplateTouched(true);
                setTemplateId(value);
              }}
              options={templates.map((template) => ({ value: template.id, label: template.name }))}
              ariaLabel="Report template"
              advanceOnSelect={false}
              searchable
            />
          </FieldBlock>
          <p className="text-sm leading-6 text-ink-muted">
            {templates.find((template) => template.id === templateId)?.description ??
              "Sets the sections, order and page size."}
          </p>
        </div>

        <div className="grid gap-3 md:grid-cols-[minmax(0,22rem)_1fr] md:items-end">
          <FieldBlock label="Transcripts in this file">
            <Dropdown
              value={transcriptOverride}
              onChange={(value) => setTranscriptOverride(value as TranscriptOverride)}
              options={TRANSCRIPT_OPTIONS}
              ariaLabel="Transcripts in this file"
              advanceOnSelect={false}
            />
          </FieldBlock>
          <p className="text-sm leading-6 text-ink-muted">
            {transcriptOverride === ""
              ? wantsTranscripts(settings)
                ? "Included, as set in stage 20."
                : "Left out, as set in stage 20."
              : transcriptOverride === "YES"
                ? "This file only. The preview may not show it."
                : "This file only."}
          </p>
        </div>

        <div className="grid gap-3 md:grid-cols-[minmax(0,22rem)_1fr] md:items-start">
          <Toggle label="Include machine-assisted text" checked={includeAiLayers} onChange={setIncludeAiLayers} />
          <div className="grid gap-1.5 text-sm leading-6 text-ink-muted">
            <p>
              {includeAiLayers
                ? "Adds accepted AI text, labelled with its model and who accepted it."
                : "Off. AI text is left out."}
            </p>
            {includeAiLayers ? (
              <p aria-live="polite" className="font-medium text-ink-700">
                {acceptedLayersUnknown ? (
                  <>
                    Couldn&rsquo;t count accepted items. Check{" "}
                    <Link href={`/design-workshops/${id}/ai-layers`} className="underline">
                      AI layers
                    </Link>
                    .
                  </>
                ) : acceptedLayerCount === null ? (
                  "Counting what has been accepted…"
                ) : acceptedLayerCount === 0 ? (
                  <>
                    Nothing accepted yet. Accept items on{" "}
                    <Link href={`/design-workshops/${id}/ai-layers`} className="underline">
                      AI layers
                    </Link>
                    .
                  </>
                ) : (
                  <>
                    {acceptedLayerCount} accepted {acceptedLayerCount === 1 ? "layer" : "layers"} will be printed.{" "}
                    <Link href={`/design-workshops/${id}/ai-layers`} className="underline">
                      Read them first
                    </Link>{" "}
                    — the preview doesn&rsquo;t show them.
                  </>
                )}
              </p>
            ) : null}
          </div>
        </div>

        <div className="border-t border-line-200 pt-4">
          <ReportAccentPicker
            value={accentOverride}
            onChange={setAccentOverride}
            palette={palette}
            savedAccent={savedAccent}
            saving={savingAccent}
            onSave={async (accent) => {
              if (!remoteId) return;
              const heldReason = heldPostReason(postRefusal);
              if (heldReason) {
                setError(
                  `${heldReason} The colour still applies to downloads from this page.`
                );
                return;
              }
              if (!online) {
                setError(
                  "Offline — colour not saved. It still applies to downloads from this page."
                );
                return;
              }
              if (stage20Pending) {
                setError(
                  "Stage 20 has unsent changes. Let them upload, then set the colour."
                );
                return;
              }
              const hex = accent.trim().toUpperCase();
              setSavingAccent(true);
              setError(null);
              try {
                await saveDesignWorkshopStage(remoteId, REPORT_STAGE_KEY, {
                  entries: [
                    {
                      entityKey: "reportSettings",
                      data: {
                        ...settings,
                        themeAccent: hex,
                        themePreset: ACCENT_PRESETS.find((p) => p.hex.toUpperCase() === hex)?.key ?? "CUSTOM"
                      }
                    }
                  ]
                });
                const reread = await refreshWorkshop();
                if (reread) setAccentOverride("");
                if (templateId) void loadPreview(templateTouched ? templateId : "");
              } catch (err) {
                setError(err instanceof Error ? err.message : "Unable to save the report colour");
              } finally {
                setSavingAccent(false);
              }
            }}
          />
        </div>

        {localOnly || !online || builtHere ? (
          <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">
            <p className="flex items-start gap-1.5 font-semibold text-ink-900">
              <CloudOff className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
              {localOnly ? "This workshop is saved on this device" : "Working without a connection"}
            </p>
            <p className="mt-1 leading-6">
              {localOnly
                ? "It uploads when you're online. "
                : ""}
              The preview and files are built from what&rsquo;s on this device.
            </p>
          </div>
        ) : null}

        {unsentStages && !builtHere ? (
          <div className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
            {unsentStages} stage{unsentStages === 1 ? " is" : "s are"} not uploaded yet, so {unsentStages === 1 ? "its" : "their"} latest
            changes aren&rsquo;t in the report.
          </div>
        ) : null}

        <div className="grid gap-2">
          <ActionGroup>
          <button
            type="button"
            className="field-button"
            disabled={downloading !== null}
            onClick={() => download("DOCX")}
          >
            <Download className="h-4 w-4" aria-hidden />
            {downloading === "DOCX" ? "Generating…" : "Download .docx"}
          </button>
          <button
            type="button"
            className="field-button-secondary"
            disabled={downloading !== null}
            onClick={() => download("PDF")}
          >
            <Download className="h-4 w-4" aria-hidden />
            {downloading === "PDF" ? "Generating…" : "Download .pdf"}
          </button>
          <button type="button" className="field-button-secondary" onClick={() => window.print()}>
            Print these pages
          </button>
          </ActionGroup>
          {preview ? (
            <span className="text-center text-xs text-ink-500">
              {preview.meta.templateName} · {pageSize} · {preview.blocks.length} blocks
              {previewAccent ? ` · ${previewAccent.toUpperCase()}` : ""}
            </span>
          ) : null}
        </div>

        {downloadWarnings ? (
          downloadWarnings.length ? (
            <div className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
              <p className="font-semibold">
                The file was generated with {downloadWarnings.length} warning{downloadWarnings.length === 1 ? "" : "s"}.
              </p>
              <ul className="mt-1 ml-5 list-disc space-y-0.5">
                {downloadWarnings.map((warning, index) => (
                  <li key={index}>{warning}</li>
                ))}
              </ul>
              {fileBuiltHere ? null : (
                <p className="mt-1 text-xs">Shortened — see the full list below.</p>
              )}
            </div>
          ) : (
            <p className="rounded-md border border-success-600/25 bg-success-100 px-3 py-2 text-sm text-success-600">
              The file was generated with no warnings.
            </p>
          )
        ) : null}
      </section>

      {remoteId ? (
        <ReportSettingsPanel
          workshopId={remoteId}
          registry={registry}
          settings={settings}
          online={online}
          draftPending={stage20Pending}
          readOnlyReason={heldPostReason(postRefusal)}
          onDraftChange={setSettingsDraft}
          onSaved={() => {
            void refreshWorkshop();
            if (templateId) void loadPreview(templateTouched ? templateId : "");
          }}
        />
      ) : null}

      {annexureWanted ? <TranscriptAnnexurePanel transcripts={transcripts} /> : null}

      <section className="panel mb-5 grid gap-3 p-4">
        <div>
          <div className="flex items-center justify-between text-sm">
            <span className="font-medium text-ink-900">Required fields across all 22 stages</span>
            <span className="text-ink-muted">{percent}%</span>
          </div>
          <ProgressBar percent={percent} className="mt-1.5 h-2" />
        </div>
        {incomplete.length ? (
          <div>
            <p className="text-sm font-medium text-ink-900">
              {incomplete.length} stage{incomplete.length === 1 ? "" : "s"} still {incomplete.length === 1 ? "has" : "have"}{" "}
              empty required fields.
            </p>
            <ul className="mt-2 grid gap-1">
              {incomplete.map(({ stage, score }) => (
                <li key={stage.key} className="text-sm">
                  <Link
                    href={`/design-workshops/${id}/stages/${stage.key}`}
                    className="font-medium text-purple-700 dark:text-purple-300 underline-offset-2 hover:underline"
                  >
                    {stage.number}. {stage.title}
                  </Link>
                  <span className="text-ink-500">
                    {" "}
                    — {score?.requiredFilled} of {score?.requiredTotal}
                    {score?.missing.length ? `: ${score.missing.slice(0, 4).join(", ")}` : ""}
                    {score && score.missing.length > 4 ? ` and ${score.missing.length - 4} more` : ""}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        ) : (
          <p className="text-sm text-ink-700">All required fields are filled in.</p>
        )}

        <details className="border-t border-line-200 pt-3">
          <summary className="cursor-pointer text-sm font-medium text-purple-700 dark:text-purple-300">
            Open a stage to correct something the preview shows
          </summary>
          <p className="mt-2 text-xs leading-5 text-ink-500">
            The report prints each stage&rsquo;s saved answers, not the artisan or product records they were copied
            from — so correct a wrong value on its stage.
          </p>
          <ul className="mt-2 grid gap-x-4 gap-y-1 sm:grid-cols-2 lg:grid-cols-3">
            {(registry?.stages ?? []).map((stage) => (
              <li key={stage.key} className="text-sm">
                <Link
                  href={`/design-workshops/${id}/stages/${stage.key}`}
                  className="text-purple-700 dark:text-purple-300 underline-offset-2 hover:underline"
                >
                  {stage.number}. {stage.title}
                </Link>
              </li>
            ))}
          </ul>
        </details>
      </section>

      {preview?.warnings.length ? (
        <section className="mb-5 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          <p className="font-semibold">
            {preview.warnings.length} thing{preview.warnings.length === 1 ? "" : "s"} the report cannot print as it stands
          </p>
          <ul className="mt-1 ml-5 list-disc space-y-0.5">
            {preview.warnings.map((warning, index) => (
              <li key={index}>{warning}</li>
            ))}
          </ul>
        </section>
      ) : null}

      {codeSpans ? (
        <section className="mb-5 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          {codeSpans} passage{codeSpans === 1 ? " is" : "s are"} marked as code — printed as plain text.
        </section>
      ) : null}

      <section className="panel rp-host p-4 sm:p-6">
        {preview === null ? (
          previewing ? (
            <p className="text-sm text-ink-700">Building the preview…</p>
          ) : previewFailed ? (
            <div className="grid justify-items-start gap-2">
              <p className="text-sm text-ink-700">
                Couldn&rsquo;t build the preview.
              </p>
              <button type="button" className="field-button-secondary" onClick={retryPreview}>
                Try again
              </button>
            </div>
          ) : (
            <p className="text-sm text-ink-700">No preview available.</p>
          )
        ) : (
          <>
            <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2 border-b border-line-200 pb-3" data-rp-noprint>
              <h2 className="font-display text-lg font-bold text-ink-900">{preview.meta.title}</h2>
              {preview.meta.subtitle ? <p className="text-sm text-ink-muted">{preview.meta.subtitle}</p> : null}
              {previewing ? <span className="text-xs text-ink-500">Refreshing…</span> : null}
            </div>
            <ReportSheets
              blocks={blocks}
              pageSize={pageSize}
              marginMm={(preview.meta as PreviewMeta).marginMm}
              headerText={headerText}
              footerText={footerText}
              mediaUrls={mediaUrls}
              palette={palette}
              paletteChosen={accentChosen}
            />
          </>
        )}
      </section>
    </>
  );
}
