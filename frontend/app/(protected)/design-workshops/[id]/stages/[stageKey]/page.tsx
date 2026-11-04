"use client";

import { ProgressBar } from "@/components/ui/ProgressBar";
import {
  Fragment,
  Suspense,
  memo,
  use,
  useCallback,
  useDeferredValue,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState
} from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { ChevronLeft, ChevronRight, CloudOff, Layers } from "lucide-react";

import { CustomSectionsForm } from "@/components/designworkshop/CustomSections";
import { ToolLoadFailed, deferredTool, registerWarm, warmDeferredTools } from "@/components/designworkshop/deferredTool";
import { HeldPostNotice, useHeldPostRefusal, writesHeld } from "@/components/designworkshop/HeldPostNotice";
import {
  CollectionTable,
  EntityForm,
  removalIsADeletion,
  rowsTheServerCouldHold,
  stageEntryBudget,
  type FieldErrors,
  type ServerHeldRows
} from "@/components/designworkshop/EntityForm";
import { StagePendingMediaProvider } from "@/components/designworkshop/FieldInput";
import { LinkedWorkshopProvider } from "@/components/designworkshop/LinkedWorkshop";
import { COSTING_STAGE, CostFindingsPanel } from "@/components/designworkshop/CostFindingsPanel";
import { PARTICIPANTS_STAGE, RosterImportPanel } from "@/components/designworkshop/RosterImportPanel";
import { CopyFromEarlierPanel } from "@/components/designworkshop/CopyFromEarlierPanel";
import { ANALYSIS_STAGE, MarketFindingsPanel } from "@/components/designworkshop/MarketFindingsPanel";
import {
  EMPTY_RECORDING_PLACE,
  StageRecordingPlaceCard,
  type StageRecordingPlace
} from "@/components/designworkshop/StageRecordingPlace";
import { PageHeader } from "@/components/PageHeader";
import {
  StagePreviewLayout,
  StagePreviewShell,
  useStagePreviewOpen
} from "@/components/designworkshop/report/StagePreviewLayout";
import type { LiveStage } from "@/components/designworkshop/report/liveDocument";
import { useLeaveInterceptor } from "@/components/UnsavedChangesGuard";
import { UploadTray } from "@/components/media/UploadTray";
import { UploadsProvider } from "@/lib/uploads";
import { ApiError } from "@/lib/api";
import { loadDesignerPrefill, prefillPatch } from "@/lib/designerPrefill";
import {
  DW_COPYABLE_STAGES,
  confirmCopiedFields,
  dwFrozenReason,
  getDesignWorkshopStage,
  saveDesignWorkshopStage,
  countedSingleton,
  pendingCopyKeys,
  splitKeptCopies,
  type DwEntity,
  type DwEntryData,
  type DwRegistry,
  type DwRow,
  type DwSaveResult,
  type DwStage,
  type DwStageCompleteness,
  type DwValue,
  type DwStageProvenance
} from "@/lib/designWorkshops";
import {
  CUSTOM_ENTITY_KEY,
  customFieldsForStage,
  sectionsForStage,
  type DwCustomSection
} from "@/lib/customSections";
import {
  adoptServerStage,
  buildStageEntries,
  copyMarksBody,
  customFieldsFor,
  emptyStage,
  ensureDraft,
  placeStageErrors,
  stageSweep,
  strandedRefusals,
  isLocalWorkshopId,
  loadCustomDefinition,
  loadDraft,
  loadRegistry,
  localStageCompleteness,
  markStagePushed,
  putDraftStage,
  scoreStageData,
  splitSingletons,
  stageDataOf,
  type CustomDefinitionSource,
  type DwDraft,
  type DwDraftStage,
  type DwRowKey,
  type RegistrySource
} from "@/lib/designWorkshopStore";
import { registryProvenanceNotice } from "@/lib/registryProvenance";
import { localWriteDecision, stageRefusalResult, stageRefusalWroteCount } from "@/lib/stageSaveOutcome";
import { isUnreachable, serverAskedForTime, triageFailure } from "@/lib/offline";
import { neverReconciled } from "@/lib/workshopOpenability";
import { FIELD_ANCHOR_ATTRIBUTE, readStageFocus } from "@/lib/workshopSearch";
import { reportServerId } from "../../report/reportTarget";
import { ActionGroup } from "@/components/ui/ActionGroup";
import {
  SETUP_STAGE,
  StageCaptureExtras,
  useComputedCounts,
  useStageAssist
} from "@/components/designworkshop/StageCaptureExtras";
import { VenuePrompt } from "@/components/designworkshop/VenuePrompt";
import { applyAutoNumbers } from "@/lib/autoNumber";

const MemoCollectionTable = memo(CollectionTable);
const MemoEntityForm = memo(EntityForm);
const MemoCustomSectionsForm = memo(CustomSectionsForm);
const NO_ROWS: DwRow[] = [];

const loadStageDocumentPreview = () => import("@/components/designworkshop/report/StageDocumentPreview");
const preloadStageDocumentPreview = () => void loadStageDocumentPreview().catch(() => undefined);
const closePreviewNoop = () => undefined;
const StageDocumentPreview = memo(
  deferredTool(() => loadStageDocumentPreview().then((module) => module.StageDocumentPreview), {
    placeholder: (props) => <StagePreviewShell open={props.open ?? true} onOpenChange={props.onOpenChange ?? closePreviewNoop} />,
    failed: (props, retry) => (
      <StagePreviewShell open={props.open ?? true} onOpenChange={props.onOpenChange ?? closePreviewNoop} failure={<ToolLoadFailed retry={retry} />} />
    )
  })
);
registerWarm(() => loadStageDocumentPreview().then(() => undefined, () => undefined));

const AUTOSAVE_MS = 800;

type StageSnapshot = {
  singleton: DwEntryData;
  collections: Record<string, DwRow[]>;
  removedFrom: string[];
  custom: DwEntryData;
};

function sameStoredValue(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (a == null || b == null) return a == null && b == null;
  if (typeof a !== "object" || typeof b !== "object") return false;
  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
    return a.every((item, index) => sameStoredValue(item, b[index]));
  }
  const left = a as Record<string, unknown>;
  const right = b as Record<string, unknown>;
  for (const key of new Set([...Object.keys(left), ...Object.keys(right)])) {
    if (!sameStoredValue(left[key], right[key])) return false;
  }
  return true;
}

function stageNeverRead(draft: DwDraft | null, stageKey: string): boolean {
  if (!draft?.remoteId) return false;
  return (draft.stages[stageKey]?.serverLoadedAt ?? null) === null;
}

function knownServerId(routeId: string): string | null | undefined {
  return isLocalWorkshopId(routeId) ? undefined : reportServerId(routeId, null);
}

function rowCounts(collections: Record<string, DwRow[]>): string {
  return Object.keys(collections)
    .sort()
    .map((key) => `${key}:${collections[key]?.length ?? 0}`)
    .join("|");
}

type Settled<T> = { ok: true; value: T } | { ok: false; error: unknown };

function settled<T>(request: Promise<T>): Promise<Settled<T>> {
  return request.then(
    (value) => ({ ok: true as const, value }),
    (error: unknown) => ({ ok: false as const, error })
  );
}

function outcomeOf<T>(outcome: Settled<T>): T {
  if (outcome.ok) return outcome.value;
  throw outcome.error;
}

function sameSnapshot(a: StageSnapshot, b: StageSnapshot): boolean {
  return (
    sameStoredValue(a.singleton, b.singleton) &&
    sameStoredValue(a.collections, b.collections) &&
    sameStoredValue(a.custom, b.custom) &&
    sameStoredValue([...a.removedFrom].sort(), [...b.removedFrom].sort())
  );
}

export default function DesignWorkshopStagePage(props: {
  params: Promise<{ id: string; stageKey: string }>;
}) {
  return (
    <UploadsProvider>
      <StagePendingMediaProvider>
        <Suspense fallback={<div className="panel p-4 text-sm text-ink-700">Loading this stage…</div>}>
          <DesignWorkshopStagePageBody {...props} />
        </Suspense>
      </StagePendingMediaProvider>
      <UploadTray />
    </UploadsProvider>
  );
}

function DesignWorkshopStagePageBody({
  params
}: {
  params: Promise<{ id: string; stageKey: string }>;
}) {
  const { id, stageKey } = use(params);
  const router = useRouter();

  const searchParams = useSearchParams();
  const focus = useMemo(() => readStageFocus(searchParams) ?? undefined, [searchParams]);

  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [registrySource, setRegistrySource] = useState<RegistrySource | null>(null);
  const registryNotice = registryProvenanceNotice(registrySource);
  const [singleton, setSingleton] = useState<DwEntryData>({});
  const [provenance, setProvenance] = useState<DwStageProvenance | null>(null);
  const [collections, setCollections] = useState<Record<string, DwRow[]>>({});
  const [errors, setErrors] = useState<Record<string, Record<string, string>>>({});
  const [unplaced, setUnplaced] = useState<string[]>([]);
  const [dropped, setDropped] = useState<string[]>([]);
  const [custom, setCustom] = useState<DwEntryData>({});
  const liveStage = useMemo(
    () => ({ singleton: singleton as LiveStage["singleton"], collections: collections as LiveStage["collections"], custom: custom as LiveStage["singleton"] }),
    [singleton, collections, custom]
  );
  const deferredLiveStage = useDeferredValue(liveStage);
  const [customSections, setCustomSections] = useState<DwCustomSection[]>([]);
  const [customSource, setCustomSource] = useState<CustomDefinitionSource>("unknown");
  const [heldCustomVersion, setHeldCustomVersion] = useState("");
  const [linkedWorkshopId, setLinkedWorkshopId] = useState<string | null>(null);
  const [serverCustomVersion, setServerCustomVersion] = useState<string | null>(null);
  const [droppedCustom, setDroppedCustom] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [previewToken, setPreviewToken] = useState(0);
  const [previewOpen, setPreviewOpen] = useStagePreviewOpen();
  const [previewMounted, setPreviewMounted] = useState(false);
  if (previewOpen && !previewMounted) setPreviewMounted(true);
  const [serverId, setServerId] = useState<string | null | undefined>(() => knownServerId(id));
  const [loading, setLoading] = useState(true);
  useEffect(() => (loading ? undefined : warmDeferredTools()), [loading]);
  const [awaitingServer, setAwaitingServer] = useState(false);
  const [neverDownloaded, setNeverDownloaded] = useState(false);
  const [unopenable, setUnopenable] = useState(false);
  const [saving, setSaving] = useState(false);
  const postRefusal = useHeldPostRefusal(serverId);
  const [assistToken, setAssistToken] = useState(0);
  const assist = useStageAssist(serverId, stageKey, assistToken);
  const [venuePrompt, setVenuePrompt] = useState(false);
  const heldNoticeId = useId();
  const [frozenReason, setFrozenReason] = useState<string | null>(null);
  const locked = saving || writesHeld(postRefusal) || frozenReason !== null;
  const [removedFrom, setRemovedFrom] = useState<string[]>([]);
  const [copiedFrom, setCopiedFrom] = useState<Record<string, string>>({});
  const [editedKeys, setEditedKeys] = useState<Set<string>>(() => new Set());
  const [confirmingCopies, setConfirmingCopies] = useState(false);

  const [stageSync, setStageSync] = useState<Pick<DwDraftStage, "dirtyAt" | "lastPushedAt" | "failure"> | null>(null);
  const [localPending, setLocalPending] = useState(false);
  const [localWriteFailed, setLocalWriteFailed] = useState(false);
  const [recordingPlace, setRecordingPlace] = useState<StageRecordingPlace>(EMPTY_RECORDING_PLACE);

  const draftIdRef = useRef<string | null>(null);
  const banked = useRef<StageSnapshot | null>(null);
  const [seedTick, setSeedTick] = useState(0);
  const serverHeld = useRef<ServerHeldRows>({});
  const flushRef = useRef<(() => Promise<void>) | null>(null);

  const stage: DwStage | null = useMemo(
    () => registry?.stages.find((candidate) => candidate.key === stageKey) ?? null,
    [registry, stageKey]
  );

  const customFields = useMemo(
    () => customFieldsForStage({ customSchemaVersion: heldCustomVersion, sections: customSections, fetchedAt: "" }, stageKey),
    [customSections, heldCustomVersion, stageKey]
  );

  const completeness: DwStageCompleteness | null = useMemo(
    () => {
      if (!stage) return null;
      const hidden = pendingCopyKeys(copiedFrom, provenance?.singleton, editedKeys);
      return scoreStageData(stage, countedSingleton(singleton, hidden), collections, customFields, custom);
    },
    [stage, singleton, collections, customFields, custom, copiedFrom, provenance, editedKeys]
  );

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setAwaitingServer(false);
    setNeverDownloaded(false);
    banked.current = null;
    serverHeld.current = {};
    setServerId(knownServerId(id));

    const seed = (draftStage: DwDraftStage | undefined) => {
      const data = stageDataOf(draftStage);
      const removed = draftStage?.removedFrom ?? [];
      const held = draftStage?.custom ?? {};
      setSingleton(data.singleton);
      setCollections(data.collections);
      setCustom(held);
      setProvenance(draftStage?.provenance ?? null);
      setRemovedFrom(removed);
      setCopiedFrom(draftStage?.copiedFrom ?? {});
      setEditedKeys(new Set());
      banked.current = { singleton: data.singleton, collections: data.collections, custom: held, removedFrom: removed };
      setSeedTick((tick) => tick + 1);
      serverHeld.current = rowsTheServerCouldHold(draftStage);
      setStageSync({
        dirtyAt: draftStage?.dirtyAt ?? null,
        lastPushedAt: draftStage?.lastPushedAt ?? null,
        failure: draftStage?.failure ?? null
      });
    };

    (async () => {
      let draft: DwDraft | null = null;
      const earlySentAt = Date.now();
      const early = isLocalWorkshopId(id) ? null : settled(getDesignWorkshopStage(id, stageKey));
      try {
        const loaded = await loadRegistry();
        if (cancelled) return;
        setRegistry(loaded.registry);
        setRegistrySource(loaded.source);

        draft = isLocalWorkshopId(id) ? await loadDraft(id) : await ensureDraft(id);
        if (cancelled) return;
        if (!draft) {
          setError(
            "This workshop isn't on this device yet. Open it where it was started, or wait for it to upload."
          );
          setLoading(false);
          return;
        }
        draftIdRef.current = draft.localId;
        setFrozenReason(dwFrozenReason({ status: draft.header.status, handedOnAt: draft.header.handedOnAt ?? null }));
        setServerId(reportServerId(id, draft));

        setLinkedWorkshopId(draft.header.workshopId ?? null);
        setHeldCustomVersion(draft.customSchemaVersion ?? "");
        setCustomSections(sectionsForStage(draft.customDefinition ?? null, stageKey));
        setCustomSource(draft.customDefinition ? "cache" : "unknown");
        void loadCustomDefinition(draft).then((held) => {
          if (cancelled) return;
          setCustomSource(held.source);
          if (!held.definition) return;
          setCustomSections(sectionsForStage(held.definition, stageKey));
          setHeldCustomVersion(held.definition.customSchemaVersion);
          setServerCustomVersion(null);
        });

        const localStage = draft.stages[stageKey];
        const spec = loaded.registry.stages.find((candidate) => candidate.key === stageKey);
        const canReconcile = Boolean(draft.remoteId && spec);
        const neverRead = (localStage?.serverLoadedAt ?? null) === null;
        const holdForServer = neverRead && canReconcile;
        if (!holdForServer) {
          seed(localStage);
          setError(null);
          setLoading(false);
        } else {
          setAwaitingServer(true);
        }

        if (!canReconcile || !spec || !draft.remoteId) return;

        const useEarly = Boolean(early) && draft.remoteId === id;
        let readSentAt = useEarly ? earlySentAt : Date.now();
        let stageData = useEarly && early ? outcomeOf(await early) : await getDesignWorkshopStage(draft.remoteId, stageKey);
        if (cancelled) return;
        setServerCustomVersion(stageData.customSchemaVersion ?? null);
        let stale = false;
        let merged = await adoptServerStage(draft.localId, spec, stageData, { readSentAt, onStale: () => (stale = true) });
        if (cancelled) return;
        if (stale) {
          readSentAt = Date.now();
          stageData = await getDesignWorkshopStage(draft.remoteId, stageKey);
          if (cancelled) return;
          setServerCustomVersion(stageData.customSchemaVersion ?? null);
          merged = await adoptServerStage(draft.localId, spec, stageData, { readSentAt });
          if (cancelled) return;
        }
        const mergedStage = merged?.stages[stageKey];
        if (mergedStage && mergedStage.dirtyAt === null) {
          seed(mergedStage);
        } else {
          if (mergedStage) {
            setStageSync({
              dirtyAt: mergedStage.dirtyAt,
              lastPushedAt: mergedStage.lastPushedAt,
              failure: mergedStage.failure
            });
          }
          if (!banked.current) seed(draft.stages[stageKey]);
        }
        setError(null);
        setAwaitingServer(false);
        setLoading(false);
      } catch (err) {
        if (cancelled) return;
        if (
          err instanceof ApiError &&
          err.status === 404 &&
          err.message !== "Unknown stage" &&
          (!draft || neverReconciled(draft))
        ) {
          setUnopenable(true);
          setAwaitingServer(false);
          setLoading(false);
          return;
        }
        if (!banked.current) seed(draft?.stages[stageKey]);
        setAwaitingServer(false);
        setLoading(false);
        if (isUnreachable(err)) {
          setNotice(
            "Offline — changes are saved here and upload when you reconnect."
          );
          if (stageNeverRead(draft, stageKey)) setNeverDownloaded(true);
          return;
        }
        const verdict = triageFailure(err);
        if (verdict.kind === "transient") {
          setError(
            `Couldn't load this stage: ${verdict.answered?.message ?? "no reason given."} Showing the copy on this device.`
          );
          if (stageNeverRead(draft, stageKey)) setNeverDownloaded(true);
          return;
        }
        setError(
          err instanceof ApiError && err.status === 404
            ? err.message === "Unknown stage"
              ? `Stage "${stageKey}" isn't available. Try again later.`
              : `Couldn't open this stage: ${err.message}`
            : err instanceof Error
              ? err.message
              : "Unable to load this stage"
        );
        if (stageNeverRead(draft, stageKey)) setNeverDownloaded(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id, stageKey]);

  useEffect(() => {
    if (!stage) return;
    const baseline = banked.current;
    if (!baseline) return;
    const target = draftIdRef.current;
    if (!target) return;
    const snapshot: StageSnapshot = { singleton, collections, custom, removedFrom };
    if (sameSnapshot(snapshot, baseline)) {
      flushRef.current = null;
      setLocalPending(false);
      return;
    }

    const payload = { singletons: splitSingletons(stage, singleton), collections, custom, removedFrom, copiedFrom };
    setLocalPending(true);
    let written = false;
    const write = async () => {
      if (written) return;
      written = true;
      const saved = await putDraftStage(target, stageKey, payload);
      const decision = localWriteDecision(saved);
      if (!decision.bank) {
        written = false;
        flushRef.current = decision.retry ? write : null;
        setLocalPending(decision.pending);
        setLocalWriteFailed(decision.failed);
        return;
      }
      banked.current = snapshot;
      flushRef.current = null;
      setLocalPending(decision.pending);
      setLocalWriteFailed(decision.failed);
      const next = saved!.stages[stageKey];
      if (next) setStageSync({ dirtyAt: next.dirtyAt, lastPushedAt: next.lastPushedAt, failure: next.failure });
    };
    flushRef.current = write;
    const timer = window.setTimeout(() => void write(), AUTOSAVE_MS);
    return () => window.clearTimeout(timer);
  }, [singleton, collections, custom, removedFrom, copiedFrom, stage, stageKey]);

  const flushLocal = useCallback(async () => {
    const write = flushRef.current;
    if (!write) return true;
    flushRef.current = null;
    await write();
    return flushRef.current === null;
  }, []);

  useEffect(() => {
    const flush = () => void flushRef.current?.();
    const onVisibility = () => {
      if (document.visibilityState === "hidden") flush();
    };
    window.addEventListener("pagehide", flush);
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      window.removeEventListener("pagehide", flush);
      document.removeEventListener("visibilitychange", onVisibility);
      flush();
    };
  }, []);

  const dropCopyMarks = useCallback((keys: string[]) => {
    setEditedKeys((current) => (keys.every((key) => current.has(key)) ? current : new Set([...current, ...keys])));
    setCopiedFrom((current) => {
      if (!keys.some((key) => key in current)) return current;
      const next = { ...current };
      for (const key of keys) delete next[key];
      return next;
    });
  }, []);

  const patchSingleton = useCallback((key: string, value: DwValue) => {
    setSingleton((current) => ({ ...current, [key]: value }));
    dropCopyMarks([key]);
  }, [dropCopyMarks]);

  const patchSingletonMany = useCallback((values: Record<string, DwValue>) => {
    if (!Object.keys(values).length) return;
    setSingleton((current) => ({ ...current, ...values }));
    dropCopyMarks(Object.keys(values));
  }, [dropCopyMarks]);

  useEffect(() => {
    if (loading || !stage || !serverId || frozenReason || !banked.current) return;
    let cancelled = false;
    void loadDesignerPrefill(serverId).then((prefill) => {
      if (cancelled || !prefill) return;
      setSingleton((current) => {
        const patch = prefillPatch(stage, current, prefill.values);
        return Object.keys(patch).length ? { ...current, ...patch } : current;
      });
    });
    return () => {
      cancelled = true;
    };
  }, [loading, stage, serverId, frozenReason, seedTick]);

  const pendingCopies = useMemo(() => {
    const labels = new Map<string, string>();
    for (const entity of stage?.entities ?? []) {
      if (entity.cardinality !== "SINGLETON") continue;
      for (const field of entity.fields) labels.set(field.key, field.label);
    }
    const keys = pendingCopyKeys(copiedFrom, provenance?.singleton, editedKeys);
    return [...keys].filter((key) => labels.has(key)).map((key) => ({ key, label: labels.get(key)! }));
  }, [stage, copiedFrom, provenance, editedKeys]);

  const keepCopies = useCallback(
    async (keys: string[]) => {
      const { local, remote } = splitKeptCopies(keys, copiedFrom);
      if (local.length) {
        setCopiedFrom((current) => {
          const next = { ...current };
          for (const key of local) delete next[key];
          return next;
        });
      }
      if (!remote.length || !serverId) return;
      setConfirmingCopies(true);
      try {
        const answer = await confirmCopiedFields(serverId, stageKey, remote);
        setProvenance((current) => ({ ...(current ?? {}), singleton: answer.provenance }));
      } catch (error) {
        setNotice(error instanceof ApiError && error.message ? error.message : "Couldn't keep the copied text. Try again.");
      } finally {
        setConfirmingCopies(false);
      }
    },
    [copiedFrom, serverId, stageKey]
  );

  const copyIntoEmpty = useCallback(
    (sourceWorkshopId: string, values: Record<string, DwValue>): number => {
      const filled: Record<string, DwValue> = {};
      for (const [key, value] of Object.entries(values)) {
        const current = singleton[key];
        const empty =
          current === undefined ||
          current === null ||
          (typeof current === "string" && !current.trim()) ||
          (Array.isArray(current) && current.length === 0);
        if (empty) filled[key] = value;
      }
      const keys = Object.keys(filled);
      if (!keys.length) return 0;
      setSingleton((current) => ({ ...current, ...filled }));
      setCopiedFrom((current) => ({
        ...current,
        ...Object.fromEntries(keys.map((key) => [key, sourceWorkshopId]))
      }));
      return keys.length;
    },
    [singleton]
  );

  const patchCustom = useCallback((key: string, value: DwValue) => {
    setCustom((current) => ({ ...current, [key]: value }));
  }, []);

  const patchCustomMany = useCallback((values: Record<string, DwValue>) => {
    if (!Object.keys(values).length) return;
    setCustom((current) => ({ ...current, ...values }));
  }, []);

  function patchCollection(entityKey: string, incoming: DwRow[], removed?: DwRow) {
    const numbering = stage?.entities.find((entity) => entity.key === entityKey);
    const rows = numbering
      ? applyAutoNumbers(numbering, collections[entityKey] ?? [], incoming, { startDate: assist?.startDate })
      : incoming;
    const shorter = rows.length < (collections[entityKey] ?? []).length;
    if (shorter && removalIsADeletion(removed, serverHeld.current[entityKey])) {
      setRemovedFrom((keys) => (keys.includes(entityKey) ? keys : [...keys, entityKey]));
    }
    setCollections((current) => ({ ...current, [entityKey]: rows }));
  }

  const interceptLeave = useLeaveInterceptor();
  const leave = useCallback(
    async (action: () => void) => {
      const go = async () => {
        try {
          await flushLocal();
        } catch {
        }
        action();
      };
      if (interceptLeave(() => void go())) return;
      await go();
    },
    [flushLocal, interceptLeave]
  );

  function applyStageResult(
    result: DwSaveResult,
    keys: DwRowKey[]
  ): { marked: number; unplacedLines: string[] } {
    const { decoded, unplaced: unplacedLines } = placeStageErrors(result.errors, keys);
    setErrors(decoded);
    setUnplaced(unplacedLines);
    setDropped(result.droppedKeys ?? []);
    setDroppedCustom(result.droppedCustomKeys ?? []);
    setServerCustomVersion(result.customSchemaVersion ?? null);
    return { marked: Object.keys(decoded).length, unplacedLines };
  }

  async function save(submit: boolean) {
    if (!stage || writesHeld(postRefusal) || frozenReason !== null) return;
    const target = draftIdRef.current;
    setSaving(true);
    setError(null);
    setNotice(null);
    setErrors({});
    setUnplaced([]);
    setDropped([]);
    setDroppedCustom([]);
    let rowKeys: DwRowKey[] = [];
    try {
      if (!(await flushLocal())) {
        setError(
          "This browser couldn't save the stage. Copy your answers somewhere safe before leaving."
        );
        return;
      }
      const draft = target ? await loadDraft(target) : null;
      const draftStage = draft?.stages[stageKey] ?? emptyStage(stageKey);
      const sinceDirtyAt = draftStage.dirtyAt;
      const sinceRemovedFrom = draftStage.removedFrom;

      const localScore = localStageCompleteness(stage, draftStage, customFieldsFor(draft, stageKey));

      const remoteId = draft?.remoteId ?? (isLocalWorkshopId(id) ? null : id);
      const offline = typeof navigator !== "undefined" && navigator.onLine === false;

      if (!remoteId || offline) {
        setNotice(
          submit
            ? localScore.isComplete
              ? "Saved on this device. All required fields are filled in."
              : `Saved on this device. Still empty: ${localScore.missing.join(", ")}.`
            : !remoteId
              ? "Saved on this device. The workshop is created online when you connect."
              : "Saved on this device. It uploads when you're online."
        );
        return;
      }

      const { entries, rowKeys: builtRowKeys, merged } = buildStageEntries(stage, draftStage);
      rowKeys = builtRowKeys;
      if (!entries.length) {
        setNotice(
          "Nothing to send from this device. Reconnect to see answers saved elsewhere."
        );
        return;
      }
      const sweep = stageSweep(stage, draftStage);
      const result = await saveDesignWorkshopStage(remoteId, stageKey, {
        entries,
        replaceCollections: sweep.replaceCollections,
        emptiedEntities: sweep.emptiedEntities,
        submit,
        ...copyMarksBody(draftStage)
      });

      serverHeld.current = rowsTheServerCouldHold(
        { serverLoadedAt: draftStage.serverLoadedAt, lastPushedAt: Date.now(), collections: draftStage.collections },
        serverHeld.current
      );

      const { marked, unplacedLines } = applyStageResult(result, rowKeys);
      if (marked || unplacedLines.length) {
        setError(
          marked
            ? "Some answers need fixing — marked below. Everything else was saved."
            : "Some answers need fixing — listed below. Everything else was saved."
        );
        return;
      }

      if (target) {
        const updated = await markStagePushed(target, stageKey, {
          completeness: result.completeness ?? null,
          sinceDirtyAt,
          sinceRemovedFrom: sweep.emptiedEntities,
          mergedEntries: merged,
          sentCopiedFrom: copyMarksBody(draftStage).copiedFrom
        });
        const next = updated?.stages[stageKey];
        if (next) setStageSync({ dirtyAt: next.dirtyAt, lastPushedAt: next.lastPushedAt, failure: next.failure });
        if (next) {
          setCopiedFrom(next.copiedFrom ?? {});
          if (next.provenance) setProvenance(next.provenance);
        }
        if (banked.current) banked.current = { ...banked.current, removedFrom: [] };
      }
      setRemovedFrom((keys) => (keys.length ? [] : keys));
      const held = sweep.withheld.length
        ? ` Deletions in ${sweep.withheld.join(", ")} will be sent next time you save online.`
        : "";
      setNotice(
        (submit
          ? "Saved. All required fields are filled in."
          : `Stage saved — ${result.created} added, ${result.updated} updated${result.removed ? `, ${result.removed} removed` : ""}.`) +
          held
      );
      setPreviewToken((current) => current + 1);
      setAssistToken((current) => current + 1);
      setVenuePrompt(stageKey === SETUP_STAGE && !singleton.venueLocation);
    } catch (err) {
      if (isUnreachable(err)) {
        setNotice(
          "Saved on this device. It uploads when you're online."
        );
        return;
      }
      if (serverAskedForTime(err)) {
        setNotice(
          "Saved on this device. It will upload in a moment."
        );
        return;
      }
      const verdict = triageFailure(err);
      if (verdict.kind === "transient") {
        setError(
          `${stage.number}. ${stage.title} couldn't be saved: ` +
            `${verdict.answered?.message ?? "No reason given."} It's safe on this device. Check the answers above.`
        );
        return;
      }
      const refusal = stageRefusalResult(err);
      if (refusal) {
        const { marked, unplacedLines } = applyStageResult(refusal, rowKeys);
        const message = err instanceof Error ? err.message : "Some answers were not accepted.";
        const wrote = stageRefusalWroteCount(refusal)
          ? ` Everything else was saved.`
          : "";
        setError(
          (marked
            ? `${message} The fields that need attention are marked below.`
            : unplacedLines.length
              ? `${message} They are listed below.`
              : message) + wrote
        );
        return;
      }
      setError(err instanceof Error ? err.message : "Unable to save this stage");
    } finally {
      setSaving(false);
    }
  }

  const stages = registry?.stages ?? [];
  const position = stages.findIndex((candidate) => candidate.key === stageKey);
  const previous = position > 0 ? stages[position - 1] : null;
  const next = position >= 0 && position < stages.length - 1 ? stages[position + 1] : null;

  function collectionErrors(entity: DwEntity, rowCount: number): Record<number, FieldErrors> {
    const out: Record<number, FieldErrors> = {};
    for (const [key, fields] of Object.entries(errors)) {
      const match = new RegExp(`^${entity.key}\\[(\\d+)\\]$`).exec(key);
      if (match && Number(match[1]) < rowCount) out[Number(match[1])] = fields;
    }
    return out;
  }

  const rowCountKey = rowCounts(collections);
  const errorsByEntity = useMemo(() => {
    const counts = new Map(
      rowCountKey
        .split("|")
        .filter(Boolean)
        .map((part) => [part.slice(0, part.lastIndexOf(":")), Number(part.slice(part.lastIndexOf(":") + 1))] as const)
    );
    const out: Record<string, Record<number, FieldErrors>> = {};
    for (const entity of stage?.entities ?? []) {
      if (entity.cardinality !== "SINGLETON") out[entity.key] = collectionErrors(entity, counts.get(entity.key) ?? 0);
    }
    return out;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stage, errors, rowCountKey]);

  const patchCollectionRef = useRef(patchCollection);
  useLayoutEffect(() => {
    patchCollectionRef.current = patchCollection;
  });
  const patchRows = useCallback((entityKey: string, rows: DwRow[]) => patchCollectionRef.current(entityKey, rows), []);
  useComputedCounts({ stageKey, assist, singleton, collections, patchSingleton: patchSingletonMany, patchRows });
  const rowWriters = useMemo(() => {
    const out: Record<string, (rows: DwRow[], removed?: DwRow) => void> = {};
    for (const entity of stage?.entities ?? []) {
      out[entity.key] = (rows, removed) => patchCollectionRef.current(entity.key, rows, removed);
    }
    return out;
  }, [stage]);

  const stranded = useMemo(
    () => strandedRefusals(errors, stage?.entities ?? [], collections),
    [errors, collections, stage]
  );

  const budget = useMemo(
    () =>
      stageEntryBudget(
        stage?.entities ?? [],
        collections,
        stage ? splitSingletons(stage, singleton) : {},
        custom
      ),
    [stage, collections, singleton, custom]
  );
  const stageEntries = useMemo(
    () => ({ rows: budget.rows, other: budget.other, otherCertain: budget.otherCertain, otherLabel: budget.otherLabel }),
    [budget.rows, budget.other, budget.otherCertain, budget.otherLabel]
  );

  const capture = useMemo(
    () => ({
      location: recordingPlace.location,
      recordedAt: recordingPlace.recordedAt,
      recordedTimezone: recordingPlace.recordedTimezone,
      point: recordingPlace.point
    }),
    [recordingPlace.location, recordingPlace.recordedAt, recordingPlace.recordedTimezone, recordingPlace.point]
  );

  const customAfterKey = useMemo(() => {
    const singletons = (stage?.entities ?? []).filter((entity) => entity.cardinality === "SINGLETON");
    return singletons.length ? singletons[singletons.length - 1].key : null;
  }, [stage]);
  const customBeforeKey = useMemo(
    () => (customAfterKey === null ? (stage?.entities[0]?.key ?? null) : null),
    [customAfterKey, stage]
  );

  const customStale = serverCustomVersion !== null && serverCustomVersion !== heldCustomVersion;

  const customBlock = (
    <MemoCustomSectionsForm
      sections={customSections}
      values={custom}
      onChange={patchCustom}
      onPatch={patchCustomMany}
      workshopId={id}
      stageKey={stageKey}
      errors={errors[CUSTOM_ENTITY_KEY]}
      disabled={locked}
      source={customSource}
      stale={customStale}
      focus={focus}
    />
  );

  const unsent = (stageSync?.dirtyAt ?? null) !== null || localPending;
  const heldBack = stageSync?.failure && !stageSync.failure.permanent ? stageSync.failure.message : null;
  const refused = stageSync?.failure?.permanent ? stageSync.failure.message : null;

  if (unopenable) {
    return (
      <>
        <PageHeader
          title="Stage"
          description="This stage could not be opened."
          icon={<Layers className="h-5 w-5" aria-hidden />}
          actions={
            <Link href="/design-workshops" className="field-button-secondary">
              All design workshops
            </Link>
          }
        />
        <section className="panel grid gap-3 p-4">
          <p className="text-sm font-medium text-ink-900">
            Workshop not found.
          </p>
          <p className="text-sm leading-6 text-ink-700">
            It doesn&rsquo;t exist or hasn&rsquo;t been shared with you. Ask its designer or an admin to add you.
          </p>
        </section>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title={stage ? `${stage.number}. ${stage.title}` : "Stage"}
        description={stage?.purpose}
        icon={<Layers className="h-5 w-5" aria-hidden />}
        actions={
          <Link href={`/design-workshops/${id}`} className="field-button-secondary">
            All stages
          </Link>
        }
      />

      {localWriteFailed ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm leading-6 text-red-700">
          Not saved — this browser couldn&rsquo;t store the stage. Don&rsquo;t close this tab; copy your answers somewhere safe.
        </div>
      ) : null}
      <HeldPostNotice refusal={postRefusal} id={heldNoticeId} sayPending />
      {frozenReason ? (
        <p className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          {frozenReason}
        </p>
      ) : null}
      <p
        role="alert"
        aria-live="assertive"
        className={error ? "mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700" : "sr-only"}
      >
        {error}
      </p>
      <p
        role="status"
        aria-live="polite"
        className={
          notice ? "mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700" : "sr-only"
        }
      >
        {notice}
      </p>
      {registryNotice ? (
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">
          {registryNotice}
        </div>
      ) : null}
      {neverDownloaded ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          Not downloaded yet — it may look empty. Blank fields won&rsquo;t overwrite answers saved elsewhere.
        </div>
      ) : null}
      {refused ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{refused}</div>
      ) : null}
      {heldBack ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">{heldBack}</div>
      ) : null}
      {unplaced.length || stranded.length ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          <p>
            {unplaced.length + stranded.length} answer
            {unplaced.length + stranded.length === 1 ? " wasn't" : "s weren't"} accepted. Everything else was saved:
          </p>
          <ul className="mt-1 list-disc pl-5">
            {[...unplaced, ...stranded].map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
          <p className="mt-1">
            Save again to retry. If it fails again, report this stage.
          </p>
        </div>
      ) : null}
      {dropped.length ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          {dropped.length} field{dropped.length === 1 ? "" : "s"} on this page couldn&rsquo;t be saved:{" "}
          {dropped.join(", ")}. Reload the page to update the app, then enter{" "}
          {dropped.length === 1 ? "it" : "them"} again.
        </div>
      ) : null}
      {droppedCustom.length ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          {droppedCustom.length} of this workshop&apos;s own question{droppedCustom.length === 1 ? "" : "s"}{" "}
          changed since this page loaded and weren&rsquo;t saved: {droppedCustom.join(", ")}. Reload to see them.
        </div>
      ) : null}

      {stage?.optionalStage ? (
        <p className="mb-4 flex flex-wrap items-center gap-2 text-sm leading-6 text-ink-muted">
          <span className="rounded-full bg-field-200 px-2 py-0.5 text-xs font-medium text-ink-700">Optional stage</span>
          Can be left empty.
        </p>
      ) : null}

      {stage?.notes ? (
        <p className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-muted">
          {stage.notes}
        </p>
      ) : null}

      <StagePreviewLayout
        open={previewOpen}
        preview={
          previewMounted ? (
            <StageDocumentPreview
              workshopId={serverId ?? id}
              draftId={id}
              stageKey={stageKey}
              stageTitle={stage?.title ?? ""}
              refreshToken={previewToken}
              localOnly={serverId === undefined ? null : serverId === null}
              live={loading ? null : deferredLiveStage}
              open={previewOpen}
              onOpenChange={setPreviewOpen}
            />
          ) : (
            <StagePreviewShell open={false} onOpenChange={setPreviewOpen} onIntent={preloadStageDocumentPreview} />
          )
        }
      >

      {loading ? (
        <div className="panel p-4 text-sm text-ink-700">
          {awaitingServer
            ?
              "Downloading this stage…"
            : "Loading this stage…"}
        </div>
      ) : !stage ? (
        <div className="panel p-4 text-sm text-ink-700">
          Stage “{stageKey}” isn&rsquo;t available. Reload to update the app.
        </div>
      ) : (
        <div className="grid gap-5">
          <StageRecordingPlaceCard value={recordingPlace} onChange={setRecordingPlace} disabled={locked} />

          {stageKey === ANALYSIS_STAGE ? (
            <MarketFindingsPanel workshopId={id} collections={collections} />
          ) : null}

          {stageKey === COSTING_STAGE ? (
            <CostFindingsPanel
              workshopId={id}
              collections={collections}
              disabled={locked}
              onUse={(sheetEntryId, key, value) =>
                patchRows(
                  "costSheet",
                  (collections.costSheet ?? []).map((row) => (row._entryId === sheetEntryId ? { ...row, [key]: value } : row))
                )
              }
            />
          ) : null}

          {venuePrompt && stageKey === SETUP_STAGE && !singleton.venueLocation ? (
            <VenuePrompt
              disabled={locked}
              onUse={(value) => patchSingletonMany({ venueLocation: value })}
              onShowField={() =>
                document
                  .querySelector(`[${FIELD_ANCHOR_ATTRIBUTE}="workshopSetup.venueLocation"]`)
                  ?.scrollIntoView({ behavior: "smooth", block: "center" })
              }
            />
          ) : null}

          <StageCaptureExtras
            stage={stage}
            workshopId={serverId ?? id}
            assist={assist}
            singleton={singleton}
            collections={collections}
            patchSingleton={patchSingletonMany}
            patchRows={patchRows}
            disabled={locked}
          />

          {DW_COPYABLE_STAGES.includes(stageKey) && serverId ? (
            <CopyFromEarlierPanel
              workshopId={serverId}
              stageKey={stageKey}
              disabled={locked}
              onCopy={copyIntoEmpty}
              pendingKeys={pendingCopies}
              confirming={confirmingCopies}
              onConfirm={(keys) => void keepCopies(keys)}
            />
          ) : null}

          {stageKey === PARTICIPANTS_STAGE && serverId ? (
            <RosterImportPanel workshopId={serverId} disabled={locked} />
          ) : null}

          <LinkedWorkshopProvider workshopId={linkedWorkshopId}>
          {stage.entities.map((entity) => (
            <Fragment key={entity.key}>
              {entity.key === customBeforeKey ? customBlock : null}
              {entity.cardinality === "SINGLETON" ? (
                <MemoEntityForm
                  entity={entity}
                  data={singleton}
                  onChange={patchSingleton}
                  onPatch={patchSingletonMany}
                  workshopId={id}
                  errors={errors[entity.key]}
                  disabled={locked}
                  stageKey={stageKey}
                  capture={capture}
                  focus={focus}
                  provenance={provenance?.singleton}
                />
              ) : (
                <MemoCollectionTable
                  entity={entity}
                  rows={collections[entity.key] ?? NO_ROWS}
                  onRowsChange={rowWriters[entity.key]}
                  workshopId={id}
                  errorsByIndex={errorsByEntity[entity.key]}
                  disabled={locked}
                  stageKey={stageKey}
                  capture={capture}
                  focus={focus}
                  provenance={provenance?.collections?.[entity.key]}
                  stageEntries={stageEntries}
                />
              )}
              {entity.key === customAfterKey ? customBlock : null}
            </Fragment>
          ))}
          {customAfterKey === null && customBeforeKey === null ? customBlock : null}
          </LinkedWorkshopProvider>

          <section className="panel grid gap-3 p-4">
            {completeness ? (
              <div>
                <div className="flex items-center justify-between text-sm">
                  <span className="font-medium text-ink-900">Required fields in this stage</span>
                  <span className="text-ink-muted">
                    {completeness.requiredFilled} of {completeness.requiredTotal}
                  </span>
                </div>
                <ProgressBar
                  percent={completeness.percent}
                  tone={completeness.isComplete ? "done" : "brand"}
                  className="mt-1.5 h-2"
                />
                {completeness.missing.length ? (
                  <p className="mt-2 text-xs leading-5 text-ink-500">Still needed: {completeness.missing.join(", ")}</p>
                ) : null}
              </div>
            ) : null}

            {removedFrom.length ? (
              <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
                Saving deletes the entries removed above. Anything added to{" "}
                {removedFrom
                  .map((key) => stage.entities.find((entity) => entity.key === key)?.title ?? key)
                  .join(", ")}{" "}
                by someone else since you loaded this page goes too.
              </p>
            ) : null}

            <div className="grid gap-2">
              <ActionGroup>
              <button
                type="button"
                className="field-button"
                disabled={locked}
                aria-describedby={postRefusal ? heldNoticeId : undefined}
                onClick={() => save(false)}
              >
                {saving ? "Saving…" : "Save stage"}
              </button>
              <button
                type="button"
                className="field-button-secondary"
                disabled={locked}
                aria-describedby={postRefusal ? heldNoticeId : undefined}
                onClick={() => save(true)}
              >
                Save and check required fields
              </button>
              </ActionGroup>
              {unsent ? (
                <span className="inline-flex w-fit items-center gap-1.5 justify-self-center rounded-full bg-amber-100 px-2.5 py-1 text-xs font-medium text-amber-800">
                  <CloudOff className="h-3.5 w-3.5" aria-hidden />
                  Saved on this device only
                </span>
              ) : stageSync?.lastPushedAt ? (
                <span className="justify-self-center text-xs text-ink-500">
                  Uploaded at {new Date(stageSync.lastPushedAt).toLocaleTimeString()}
                </span>
              ) : null}
            </div>
          </section>

          <nav className="flex flex-wrap items-center justify-between gap-2" aria-label="Stage navigation">
            {previous ? (
              <button
                type="button"
                className="field-button-secondary"
                onClick={() => void leave(() => router.push(`/design-workshops/${id}/stages/${previous.key}`))}
              >
                <ChevronLeft className="h-4 w-4" aria-hidden />
                {previous.number}. {previous.title}
              </button>
            ) : (
              <span />
            )}
            {next ? (
              <button
                type="button"
                className="field-button-secondary"
                onClick={() => void leave(() => router.push(`/design-workshops/${id}/stages/${next.key}`))}
              >
                {next.number}. {next.title}
                <ChevronRight className="h-4 w-4" aria-hidden />
              </button>
            ) : null}
          </nav>
        </div>
      )}
      </StagePreviewLayout>
    </>
  );
}
