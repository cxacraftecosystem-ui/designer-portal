"use client";

import { Toggle } from "@/components/ui/Toggle";
import { useEffect, useId, useMemo, useRef, useState } from "react";
import { Lock, Plus } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { LIST_PAGE_CEILING, listCut, mergeById, type ListCut } from "@/components/data/cappedList";
import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";
import { Field, Select, TextInput } from "@/components/FormControls";
import { appendDictatedPhrase } from "@/components/richtext/dictatedValue";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { RichTextField } from "@/components/richtext/RichTextField";
import { FieldProvenance } from "@/components/FieldProvenance";
import { CarryContextBanner, carryScope, useCarryContext, type CarryScopeState } from "@/components/forms/CarryContextBanner";
import type { InlineRecordSurfaceProps } from "@/components/forms/inlineRecordHost";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { optionToProduct, productToOption, useRecordOffPage } from "@/components/forms/recordPickers";
import { useWorkshopPicker, WorkshopPicker } from "@/components/forms/WorkshopPicker";
import {
  HELD_POST_PENDING_FILES,
  HeldPostNotice,
  MEDIA_HELD_LABEL,
  mediaListNotice,
  mediaWorkshopIds,
  mediaWriteHold,
  useHeldPostRefusals,
  useRecordFilingHold,
  writesHeld,
  type HeldPostRefusal
} from "@/components/designworkshop/HeldPostNotice";
import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { StatusBadge } from "@/components/StatusBadge";
import { Dropdown } from "@/components/ui/Dropdown";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { apiFetch, listResource } from "@/lib/api";
import { listAllPages } from "@/lib/listAllPages";
import { countNoun, formatDate } from "@/lib/format";
import { handleFormEnter } from "@/lib/formNav";
import { describePreProcess, describeProcessStep, renameMediaFile, uploadMediaFile } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { processBody } from "@/lib/recordPayloads";
import { loadCachedRegister } from "@/lib/referenceCache";
import {
  cachedListLine,
  deviceLooksOffline,
  workshopEmptyLabel,
  workshopListNotice,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";
import { hasRank } from "@/lib/permissions";
import type { Artisan, ExtraMetadata, MediaFile, ProductDocumentation, RecordStatus, User, Workshop } from "@/lib/types";
import { useConfirm } from "@/components/dialogs";
import { ActionGroup } from "@/components/ui/ActionGroup";

export type ProcessStepRecord = {
  id: string;
  name: string;
  stepType: string;
  sortOrder?: number;
  notes?: string | null;
  media?: MediaFile[];
};

export type ProcessRecord = {
  id: string;
  name: string;
  productId: string;
  preProcessAvailable?: boolean;
  notes?: string | null;
  status: RecordStatus | string;
  steps?: ProcessStepRecord[];
  media?: MediaFile[];
  product?: ProductDocumentation | null;
  workshopId?: string | null;
  designWorkshopId?: string | null;
  workshop?: Workshop | null;
  extraMetadata?: ExtraMetadata | null;
  createdAt: string;
  createdById?: string;
  createdBy?: User;
};

const STATUS_OPTIONS = ["DRAFT", "PENDING", "APPROVED", "REJECTED"];

type StepUi = {
  key: string;
  serverId?: string;
  name: string;
  stepType: string;
  recordAdditional: boolean;
  notes: string;
  files: File[];
  existingMedia: MediaFile[];
  nameError?: string | null;
};

function stepTypeLabel(stepType: string): string {
  return stepType === "SEQUENTIAL" ? "Sequential" : "Group of activities";
}

function makeKey(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

export function hasUnsavedWork({
  saving,
  committed,
  signature,
  initialSignature
}: {
  saving: boolean;
  committed: boolean;
  signature: string;
  initialSignature: string;
}): boolean {
  return !saving && !committed && signature !== initialSignature;
}

function SavedMediaList({
  items,
  recordHold,
  onRemoved,
  onError
}: {
  items: MediaFile[];
  recordHold: HeldPostRefusal;
  onRemoved: (id: string) => void;
  onError: (message: string) => void;
}) {
  const [active, setActive] = useState<PreviewMedia | null>(null);
  const [removingId, setRemovingId] = useState<string | null>(null);
  const confirm = useConfirm();
  const heldFor = useHeldPostRefusals(items.flatMap(mediaWorkshopIds));
  const holdOf = (media: MediaFile): HeldPostRefusal => mediaWriteHold([recordHold, heldFor(mediaWorkshopIds(media))]);
  const heldNoticeId = useId();
  if (!items.length) return null;

  async function remove(media: MediaFile) {
    if (writesHeld(holdOf(media))) return;
    const ok = await confirm({
      title: "Remove this file?",
      body: `"${media.caption || media.originalFilename}" will be removed from this step.`,
      note: "This can't be undone.",
      confirmLabel: "Remove file",
      tone: "danger"
    });
    if (!ok) return;
    setRemovingId(media.id);
    try {
      await apiFetch(`/media/${media.id}`, { method: "DELETE" });
      onRemoved(media.id);
    } catch (err) {
      onError(err instanceof Error ? err.message : "Unable to remove media");
    } finally {
      setRemovingId(null);
    }
  }

  return (
    <div className="grid gap-2">
      <p className="text-xs text-ink-500">Already attached:</p>
      <HeldPostNotice
        refusal={mediaListNotice(items.map(holdOf), "removed")}
        id={heldNoticeId}
        className=""
        sayPending
        pendingSentence={HELD_POST_PENDING_FILES}
      />
      <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
        {items.map((media) => {
          const preview: PreviewMedia = {
            key: media.id,
            id: media.id,
            name: media.originalFilename,
            mediaType: media.mediaType,
            mimeType: media.mimeType,
            sizeBytes: media.sizeBytes,
            url: media.url,
            caption: media.caption,
            transcriptStatus: media.transcriptStatus,
            transcriptText: media.transcriptText,
            transcriptError: media.transcriptError
          };
          const hold = holdOf(media);
          return (
            <MediaPreviewTile
              key={media.id}
              item={preview}
              onOpen={() => setActive(preview)}
              onRemove={removingId === media.id ? undefined : () => remove(media)}
              removeLabel="Remove"
              removeHeld={writesHeld(hold)}
              removeDescribedBy={typeof hold === "string" ? heldNoticeId : undefined}
              statusLabel={typeof hold === "string" ? MEDIA_HELD_LABEL : null}
            />
          );
        })}
      </div>
      {active ? <MediaLightbox item={active} onClose={() => setActive(null)} /> : null}
    </div>
  );
}

function splitNotes(value: string): string[] {
  return value
    .split(/\n\s*\n/)
    .map((note) => note.trim())
    .filter(Boolean);
}

function MultiNoteInput({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  const [rows, setRows] = useState<string[]>(() => {
    const split = splitNotes(value);
    return split.length ? split : [""];
  });

  function emit(updated: string[]) {
    const next = updated.length ? updated : [""];
    setRows(next);
    onChange(
      next
        .map((note) => note.trim())
        .filter(Boolean)
        .join("\n\n")
    );
  }

  return (
    <div className="grid gap-2">
      <span className="text-sm font-semibold text-ink-900">{label}</span>
      {rows.map((note, index) => (
        <div key={index} className="grid gap-1">
          <div className="flex items-start gap-2">
            <textarea
              className="field-input min-h-16 flex-1"
              rows={2}
              placeholder={rows.length > 1 ? `Note ${index + 1}` : "Note"}
              value={note}
              onChange={(event) => emit(rows.map((n, i) => (i === index ? event.target.value : n)))}
            />
            {rows.length > 1 ? (
              <button
                type="button"
                aria-label="Remove note"
                className="field-button-secondary h-9 min-h-0 shrink-0 px-2.5"
                onClick={() => emit(rows.filter((_, i) => i !== index))}
              >
                ✕
              </button>
            ) : null}
          </div>
          <OnDeviceDictationButton
            fieldLabel={rows.length > 1 ? `${label}, note ${index + 1}` : label}
            explainWhenUnavailable={false}
            onCommit={(phrase) => {
              emit(rows.map((n, i) => (i === index ? appendDictatedPhrase(note, phrase) : n)));
            }}
          />
        </div>
      ))}
      <button type="button" className="field-button-secondary h-9 min-h-0 justify-self-start px-3 text-xs" onClick={() => emit([...rows, ""])}>
        <Plus className="h-3.5 w-3.5" aria-hidden />
        Add note
      </button>
    </div>
  );
}

export function ProcessForm({
  initial,
  footerFields,
  onDone,
  onCancel,
  onDiscardAndLeave,
  onCreated,
  onQueued
}: {
  initial?: ProcessRecord;
  onDone: () => void;
  onCancel: () => void;
  onCreated?: (record: ProcessRecord) => void;
  onQueued?: () => void;
} & InlineRecordSurfaceProps) {
  const { user } = useAuth();
  const isEdit = Boolean(initial);
  const canPickStatus = hasRank(user, "PROFESSOR");
  const workshop = useWorkshopPicker({
    initialWorkshopId: initial?.workshopId,
    initialDesignWorkshopId: initial?.designWorkshopId,
    isEdit,
    resetKey: initial?.id ?? null
  });
  const filing = useRecordFilingHold(initial?.designWorkshopId, workshop.designWorkshopId, "process");
  const filingNoticeId = useId();

  const [name, setName] = useState(initial?.name ?? "");
  const [artisanId, setArtisanId] = useState(initial?.product?.artisanId ?? "");
  const [productId, setProductId] = useState(initial?.productId ?? "");
  const [preProcessAvailable, setPreProcessAvailable] = useState(initial?.preProcessAvailable ?? false);
  const [notes, setNotes] = useState(initial?.notes ?? "");
  const [status, setStatus] = useState<string>(initial?.status ?? (canPickStatus ? "APPROVED" : "PENDING"));
  const [steps, setSteps] = useState<StepUi[]>(() =>
    (initial?.steps ?? []).map((step) => ({
      key: makeKey(),
      serverId: step.id,
      name: step.name,
      stepType: step.stepType,
      recordAdditional: Boolean(step.notes && step.notes.trim()),
      notes: step.notes ?? "",
      files: [],
      existingMedia: step.media ?? []
    }))
  );
  const [preFiles, setPreFiles] = useState<File[]>([]);
  const [existingPreMedia, setExistingPreMedia] = useState<MediaFile[]>(initial?.media ?? []);

  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [artisanListState, setArtisanListState] = useState<CarryScopeState>("pending");
  const [artisanProducts, setArtisanProducts] = useState<ProductDocumentation[]>([]);
  const [artisanCut, setArtisanCut] = useState<ListCut | null>(null);
  const [productCut, setProductCut] = useState<ListCut | null>(null);
  const [productsLoading, setProductsLoading] = useState(false);
  const [productLoadError, setProductLoadError] = useState<string | null>(null);
  const [productCache, setProductCache] = useState<{ artisanId: string; cachedAt: string } | null>(null);

  const [error, setError] = useState<string | null>(null);
  const [nameError, setNameError] = useState<string | null>(null);
  const [artisanError, setArtisanError] = useState<string | null>(null);
  const [productError, setProductError] = useState<string | null>(null);
  const [stepsError, setStepsError] = useState<string | null>(null);
  const [preMediaError, setPreMediaError] = useState<string | null>(null);
  const formId = useId();
  const errorId = `${formId}-error`;
  const nameErrorId = `${formId}-name-error`;
  const artisanErrorId = `${formId}-artisan-error`;
  const productErrorId = `${formId}-product-error`;
  const preMediaErrorId = `${formId}-pre-media-error`;
  const stepsErrorId = `${formId}-steps-error`;
  const [saving, setSaving] = useState(false);
  const [committed, setCommitted] = useState(false);
  const [uploadNote, setUploadNote] = useState<string | null>(null);
  const [addMenu, setAddMenu] = useState(false);
  const [guardOpen, setGuardOpen] = useState(false);
  const [promptFromCancel, setPromptFromCancel] = useState(false);

  const statusChoices = initial?.status && !STATUS_OPTIONS.includes(String(initial.status)) ? [String(initial.status), ...STATUS_OPTIONS] : STATUS_OPTIONS;

  useEffect(() => {
    listAllPages<Artisan>("/artisans", { pageSize: LIST_PAGE_CEILING })
      .then((result) => {
        setArtisans(result.items);
        setArtisanCut(listCut(result, "artisans"));
        setArtisanListState("loaded");
      })
      .catch(() => setArtisanListState("unavailable"));
  }, []);

  const offPageArtisan = useRecordOffPage<Artisan>("/artisans", artisanId, artisans);
  const artisanOptions = useMemo(
    () => (offPageArtisan ? mergeById(artisans, [offPageArtisan]) : artisans),
    [artisans, offPageArtisan]
  );

  const artisanList: WorkshopListState<Artisan> = useMemo(() => {
    if (artisanListState === "pending") return { kind: "loading" };
    if (artisanListState === "unavailable") return { kind: "failed" };
    return { kind: "ok", rows: artisanOptions, total: artisanCut?.total ?? artisanOptions.length };
  }, [artisanListState, artisanOptions, artisanCut]);
  const artisanVoice: WorkshopListVoice = {
    table: "field",
    noun: "artisans",
    scoped: false,
    accessList: false,
    cached: true,
    online: !deviceLooksOffline()
  };
  const artisanNotice = workshopListNotice(artisanList, artisanVoice);

  const artisanRequired = artisanOptions.length > 0;
  const productRequired = artisanProducts.length > 0;

  const carriedProductRef = useRef<string | null>(null);

  const carry = useCarryContext({
    enabled: !isEdit,
    scopes: [carryScope("artisan", artisanListState, artisanOptions)],
    applies: ["artisan", "product", "workshop"],
    onApply: (context) => {
      if (context.artisanId) setArtisanId(context.artisanId);
      if (context.productId) carriedProductRef.current = context.productId;
      if (context.workshopId && !workshop.touched) workshop.setWorkshopId(context.workshopId);
    }
  });
  const pruneCarried = carry.prune;
  function clearCarriedContext() {
    carry.change();
    carriedProductRef.current = null;
    setArtisanId("");
    setProductId("");
  }

  useEffect(() => {
    let cancelled = false;
    if (!artisanId) {
      setArtisanProducts([]);
      setProductsLoading(false);
      setProductLoadError(null);
      return;
    }
    setProductsLoading(true);
    setProductLoadError(null);
    const artisanName = artisanOptions.find((artisan) => artisan.id === artisanId)?.name?.trim();
    let settled: ProductDocumentation[] = [];
    void loadCachedRegister<ProductDocumentation>({
      model: "product",
      filterValue: artisanId,
      decode: optionToProduct,
      encode: productToOption,
      fetch: async () => {
        const result = await listResource<ProductDocumentation>("/products", {
          artisanId,
          artisanName,
          pageSize: LIST_PAGE_CEILING
        });
        if (!cancelled) setProductCut(listCut(result, "products of this artisan"));
        return result.items;
      },
      onList: (rows, cachedAt) => {
        if (cancelled) return;
        settled = rows;
        setArtisanProducts(rows);
        setProductsLoading(false);
        setProductCache(cachedAt ? { artisanId, cachedAt } : null);
      }
    }).then((outcome) => {
      if (cancelled) return;
      setProductsLoading(false);
      if (outcome.source === "none") {
        setArtisanProducts([]);
        setProductLoadError(
          "Couldn't load products. Tap the artisan again to retry."
        );
        return;
      }
      setProductLoadError(null);
      const carried = carriedProductRef.current;
      if (carried) {
        carriedProductRef.current = null;
        if (settled.some((product) => product.id === carried)) {
          setProductId(carried);
          return;
        }
        pruneCarried("product");
      }
      setProductId((current) => (current && settled.every((product) => product.id !== current) ? "" : current));
    });
    return () => {
      cancelled = true;
    };
  }, [artisanId, artisanOptions, pruneCarried]);

  const carriedArtisanId = carry.applied?.context.artisanId ?? null;
  const carriedProductId = carry.applied?.context.productId ?? null;
  const signature = JSON.stringify({
    name,
    artisanId: artisanId && artisanId === carriedArtisanId ? "" : artisanId,
    productId: productId && productId === carriedProductId ? "" : productId,
    workshopId: workshop.touched ? workshop.workshopId : "",
    designWorkshopId: workshop.touched ? workshop.designWorkshopId : "",
    status,
    notes,
    preProcessAvailable,
    pre: preFiles.length,
    steps: steps.map((step) => ({
      id: step.serverId ?? null,
      name: step.name,
      type: step.stepType,
      notes: step.recordAdditional ? step.notes : "",
      files: step.files.length
    }))
  });
  const [initialSignature] = useState(signature);
  const dirty = hasUnsavedWork({ saving, committed, signature, initialSignature });

  useLeaveGuard(dirty, () => setGuardOpen(true));

  useEffect(() => {
    if (!dirty) return;
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);

  function updateStep(key: string, patch: Partial<StepUi>) {
    setSteps((current) => current.map((step) => (step.key === key ? { ...step, ...patch } : step)));
  }

  function addStep(stepType: string) {
    setSteps((current) => [
      ...current,
      { key: makeKey(), name: "", stepType, recordAdditional: false, notes: "", files: [], existingMedia: [] }
    ]);
    setAddMenu(false);
  }

  function requestCancel() {
    if (dirty) {
      setPromptFromCancel(true);
      setGuardOpen(true);
    } else onCancel();
  }

  function leaveAfterDiscard() {
    if (onDiscardAndLeave) onDiscardAndLeave();
    else onCancel();
  }

  async function submit(): Promise<void> {
    if (committed) return;
    if (filing.saveHeld) return;
    setError(null);
    setNameError(null);
    setArtisanError(null);
    setProductError(null);
    setStepsError(null);
    setPreMediaError(null);

    let blocked = false;
    let focusId: string | null = null;
    if (!name.trim()) {
      setNameError("This field cannot be empty");
      blocked = true;
      focusId = focusId ?? "process-name";
    }
    if (artisanRequired && !artisanId) {
      setArtisanError("Please select an artisan");
      blocked = true;
    }
    if (productRequired && !productId) {
      setProductError("Please select a product");
      blocked = true;
    }
    if (preProcessAvailable && preFiles.length === 0 && existingPreMedia.length === 0) {
      setPreMediaError("Attach the pre-process media or uncheck the box");
      blocked = true;
    }
    if (steps.length === 0) {
      setStepsError("Add at least one step");
      blocked = true;
    }
    setSteps((current) =>
      current.map((step) => ({ ...step, nameError: step.name.trim() ? null : "This field cannot be empty" }))
    );
    steps.forEach((step) => {
      if (!step.name.trim()) {
        blocked = true;
        focusId = focusId ?? `step-name-${step.key}`;
      }
    });
    if (blocked) {
      setError("Please fill the required fields highlighted above.");
      setGuardOpen(false);
      if (focusId) document.getElementById(focusId)?.focus();
      return;
    }

    if (!(await workshop.confirmSubmission())) {
      setGuardOpen(false);
      return;
    }

    setSaving(true);
    try {
      const trimmedName = name.trim();
      const payload = processBody({
        name,
        productId,
        workshopId: workshop.workshopId || null,
        designWorkshopId: workshop.designWorkshopId || null,
        preProcessAvailable,
        notes,
        status,
        steps,
        isEdit,
        now: new Date()
      });
      const outcome = await saveOrQueue<ProcessRecord>({
        label: `Process · ${trimmedName || "Untitled"}`,
        endpoint: isEdit ? `/processes/${initial!.id}` : "/processes",
        method: isEdit ? "PATCH" : "POST",
        body: payload,
        media: [
          {
            files: preProcessAvailable
              ? preFiles.map((file, index) =>
                  renameMediaFile(file, {
                    recordType: "Process",
                    recordName: trimmedName,
                    descriptor: describePreProcess(file, existingPreMedia.length + index + 1)
                  })
                )
              : [],
            linkedRecordType: "process",
            caption: `Pre-process media for ${trimmedName}`
          },
          ...steps.map((step, index) => ({
            files: step.files.map((file, fileIndex) =>
              renameMediaFile(file, {
                recordType: "Process",
                recordName: trimmedName,
                descriptor: describeProcessStep({
                  stepNumber: index + 1,
                  stepName: step.name.trim(),
                  subject: file,
                  index: step.existingMedia.length + fileIndex + 1
                })
              })
            ),
            linkedRecordType: "processstep",
            caption: `Process step ${step.name}`,
            stepIndex: index
          }))
        ]
      });
      setCommitted(true);
      const savedArtisan = artisanOptions.find((a) => a.id === artisanId);
      const savedProduct = artisanProducts.find((product) => product.id === productId);
      carry.remember({
        artisanId,
        artisanName: savedArtisan?.name ?? null,
        place: savedArtisan?.place ?? null,
        craftId: savedArtisan?.craftId ?? null,
        productId,
        productName: savedProduct?.productName ?? null,
        workshopId: workshop.workshopId,
        workshopName: workshop.workshops.find((w) => w.id === workshop.workshopId)?.title ?? null
      });
      if (outcome.queued) {
        setGuardOpen(false);
        setSaving(false);
        if (onQueued) onQueued();
        else onDone();
        return;
      }
      const saved = outcome.saved;

      const failures: string[] = [];
      const totalUploads = (preProcessAvailable ? preFiles.length : 0) + steps.reduce((sum, step) => sum + step.files.length, 0);
      let uploadCount = 0;
      const note = () => {
        uploadCount += 1;
        if (totalUploads) setUploadNote(`Uploading media ${uploadCount} of ${totalUploads}…`);
      };

      if (preProcessAvailable) {
        for (let index = 0; index < preFiles.length; index += 1) {
          note();
          try {
            await uploadMediaFile({
              file: renameMediaFile(preFiles[index], {
                recordType: "Process",
                recordName: saved.name || trimmedName,
                descriptor: describePreProcess(preFiles[index], existingPreMedia.length + index + 1)
              }),
              linkedRecordType: "process",
              linkedRecordId: saved.id,
              caption: `Pre-process media for ${trimmedName}`
            });
          } catch {
            failures.push(preFiles[index].name);
          }
        }
      }
      const savedSteps = saved.steps ?? [];
      for (let index = 0; index < savedSteps.length; index += 1) {
        const serverStep = savedSteps[index];
        const local = steps[index];
        if (!local) continue;
        for (let fileIndex = 0; fileIndex < local.files.length; fileIndex += 1) {
          note();
          try {
            await uploadMediaFile({
              file: renameMediaFile(local.files[fileIndex], {
                recordType: "Process",
                recordName: saved.name || trimmedName,
                descriptor: describeProcessStep({
                  stepNumber: serverStep.sortOrder ?? index + 1,
                  stepName: serverStep.name,
                  subject: local.files[fileIndex],
                  index: local.existingMedia.length + fileIndex + 1
                })
              }),
              linkedRecordType: "processstep",
              linkedRecordId: serverStep.id,
              caption: `Process step ${serverStep.name}`
            });
          } catch {
            failures.push(local.files[fileIndex].name);
          }
        }
      }
      setUploadNote(null);
      if (failures.length) {
        setError(
          `Saved, but these didn't upload: ${failures.join(", ")}. Reopen to retry.`
        );
        setGuardOpen(false);
        if (onCreated) onCreated(saved);
        return;
      }
      setGuardOpen(false);
      if (onCreated) {
        onCreated(saved);
        return;
      }
      onDone();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save process");
      setGuardOpen(false);
    } finally {
      setSaving(false);
      setUploadNote(null);
    }
  }

  const productPlaceholder = !artisanId
    ? "Select an artisan first"
    : productsLoading
      ? "Loading products…"
      : artisanProducts.length === 0
        ? "No products for this artisan"
        : "Select the product this process makes";

  return (
    <form
      className="panel grid gap-4 p-5"
      onKeyDown={handleFormEnter}
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
    >
      <div>
        <p className="mt-1 text-xs text-ink-500">
          How a product is made, step by step.
        </p>
      </div>
      {initial ? <FieldProvenance extraMetadata={initial.extraMetadata} /> : null}
      {error ? (
        <div id={errorId} role="alert" className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">
          {error}
        </div>
      ) : null}
      <CarryContextBanner offer={carry.applied} onChange={clearCarriedContext} />

      <DictationUnavailableNotice />

      <WorkshopPicker state={workshop} saving={saving} disabled={filing.recordHeld} />

      <div>
        <DictatedTextInput
          id="process-name"
          label="Name of the process"
          required
          titleCased
          explainWhenUnavailable={false}
          value={name}
          aria-invalid={!!nameError}
          aria-describedby={nameError ? nameErrorId : undefined}
          onChange={(next) => setName(next)}
        />
        {nameError ? (
          <p id={nameErrorId} className="mt-1 text-xs text-error-600">
            {nameError}
          </p>
        ) : null}
      </div>

      <div>
        <Field label="Artisan" required={artisanRequired}>
          <Dropdown
            value={artisanId}
            describedBy={artisanError ? artisanErrorId : undefined}
            emptyLabel={workshopEmptyLabel(artisanList, artisanVoice)}
            disabled={artisanList.kind !== "loading" && artisanOptions.length === 0}
            searchable
            options={[
              { value: "", label: "Select the artisan" },
              ...artisanOptions.map((artisan) => ({ value: artisan.id, label: `${artisan.name} · ${artisan.place}` }))
            ]}
            onChange={(picked) => {
              if (picked !== artisanId) {
                setArtisanId(picked);
                setProductId("");
                const artisan = artisanOptions.find((a) => a.id === picked);
                if (artisan) {
                  carry.remember({ artisanId: artisan.id, artisanName: artisan.name, place: artisan.place }, { explicit: true });
                }
              }
            }}
          />
        </Field>
        <CappedListNotice cuts={[artisanCut]} />
        {artisanNotice ? <p className="mt-1 text-xs leading-5 text-ink-500">{artisanNotice}</p> : null}
        {artisanError ? (
          <p id={artisanErrorId} role="alert" className="mt-1 text-xs text-error-600">
            {artisanError}
          </p>
        ) : null}
      </div>

      <div>
        <Field label="Product" required={productRequired}>
          <Dropdown
            value={productId}
            describedBy={productError ? productErrorId : undefined}
            searchable
            options={[
              { value: "", label: productPlaceholder },
              ...artisanProducts.map((product) => ({ value: product.id, label: product.productName }))
            ]}
            onChange={(picked) => {
              setProductId(picked);
              const product = artisanProducts.find((candidate) => candidate.id === picked);
              if (!product) return;
              pruneCarried("product");
              const artisan = artisanOptions.find((candidate) => candidate.id === artisanId);
              carry.remember({
                artisanId,
                artisanName: artisan?.name ?? null,
                place: artisan?.place ?? null,
                craftId: artisan?.craftId ?? null,
                productId: product.id,
                productName: product.productName
              });
            }}
            disabled={!artisanId || productsLoading || artisanProducts.length === 0}
          />
        </Field>
        {productsLoading ? <p className="mt-1 text-xs text-ink-500">Loading this artisan&apos;s products…</p> : null}
        {!productsLoading && productLoadError ? <p className="mt-1 text-xs text-error-600">{productLoadError}</p> : null}
        {!productsLoading && !productLoadError && artisanId && artisanProducts.length === 0 ? (
          <p className="mt-1 text-xs text-ink-500">No products yet. Create one first.</p>
        ) : null}
        {!productsLoading && !productLoadError && artisanId && artisanProducts.length > 0 && !productCut ? (
          <p className="mt-1 text-xs text-ink-500">
            {productCache && productCache.artisanId === artisanId
              ? cachedListLine(artisanProducts.length, "products", formatDate(productCache.cachedAt))
              : `${countNoun(artisanProducts.length, "product")} available for this artisan.`}
          </p>
        ) : null}
        {!productsLoading && !productLoadError && artisanId ? <CappedListNotice cuts={[productCut]} /> : null}
        {productError ? (
          <p id={productErrorId} role="alert" className="mt-1 text-xs text-error-600">
            {productError}
          </p>
        ) : null}
      </div>

      <RichTextField
        name="notes"
        label="What happens in this process"
        defaultValue={initial?.notes ?? ""}
        helper="In your own words. Printed under “What happens”."
        className="md:col-span-2"
        onValueChange={setNotes}
        explainWhenUnavailable={false}
      />

      <Toggle label="Pre-processes available" checked={preProcessAvailable} onChange={setPreProcessAvailable} />
      {preProcessAvailable ? (
        <div className="grid gap-2">
          <p className="text-xs text-ink-500">Attach the pre-process media (required).</p>
          <SavedMediaList
            items={existingPreMedia}
            recordHold={filing.stored}
            onRemoved={(id) => setExistingPreMedia((current) => current.filter((media) => media.id !== id))}
            onError={setError}
          />
          <p className="text-xs font-medium text-amber-800 dark:text-amber-100">Video works best here.</p>
          <MediaCaptureField
            files={preFiles}
            onFilesChange={setPreFiles}
            title="Attach media"
            description="Photos, video, audio and files."
            stagingOwnerId={`${formId}:pre-process`}
            aria-describedby={preMediaError ? preMediaErrorId : undefined}
          />
          {preMediaError ? (
            <p id={preMediaErrorId} role="alert" className="text-xs text-error-600">
              {preMediaError}
            </p>
          ) : null}
        </div>
      ) : null}

      <div className="border-t border-line-200 pt-4">
        <h3 className="font-display font-bold text-ink-900">Steps</h3>
        {stepsError ? (
          <p id={stepsErrorId} role="alert" className="mt-1 text-xs text-error-600">
            {stepsError}
          </p>
        ) : null}

        <div className="mt-3 grid gap-3">
          {steps.map((step, index) => (
            <div key={step.key} className="grid gap-3 rounded-xl border border-line-200 bg-surface-50 p-4">
              <div className="flex items-center justify-between gap-2">
                <span className="text-sm font-semibold text-ink-900">
                  Step {index + 1} · {stepTypeLabel(step.stepType)}
                </span>
                <button
                  type="button"
                  className="text-sm font-semibold text-error-600"
                  onClick={() => setSteps((current) => current.filter((s) => s.key !== step.key))}
                >
                  Remove
                </button>
              </div>
              <div>
                <DictatedTextInput
                  id={`step-name-${step.key}`}
                  label="Name of the step"
                  required
                  explainWhenUnavailable={false}
                  value={step.name}
                  aria-invalid={!!step.nameError}
                  aria-describedby={step.nameError ? `step-name-${step.key}-error` : undefined}
                  onChange={(next) => updateStep(step.key, { name: next })}
                />
                {step.nameError ? (
                  <p id={`step-name-${step.key}-error`} className="mt-1 text-xs text-error-600">
                    {step.nameError}
                  </p>
                ) : null}
              </div>
              <SavedMediaList
                items={step.existingMedia}
                recordHold={filing.stored}
                onRemoved={(id) => updateStep(step.key, { existingMedia: step.existingMedia.filter((media) => media.id !== id) })}
                onError={setError}
              />
              <Toggle
                label="Record additional information"
                checked={step.recordAdditional}
                onChange={(next) => updateStep(step.key, { recordAdditional: next, ...(next ? {} : { notes: "" }) })}
              />
              {step.recordAdditional ? (
                <MultiNoteInput
                  key={step.key}
                  label="Additional context for this step"
                  value={step.notes}
                  onChange={(value) => updateStep(step.key, { notes: value })}
                />
              ) : null}
              <p className="text-xs font-medium text-amber-800 dark:text-amber-100">Video works best here.</p>
              <MediaCaptureField
                files={step.files}
                onFilesChange={(files) => updateStep(step.key, { files })}
                title="Attach media"
                description="Photos, video, audio and files."
              />
            </div>
          ))}
        </div>

        <div className="relative mt-3">
          <button type="button" className="field-button-secondary w-full" onClick={() => setAddMenu((value) => !value)}>
            <Plus className="h-4 w-4" aria-hidden />
            Add Another Step
          </button>
          {addMenu ? (
            <div className="absolute left-0 right-0 z-20 mt-1 rounded-md border border-line-200 bg-card p-1 shadow-md">
              <button
                type="button"
                className="block w-full rounded px-3 py-2 text-left text-sm text-ink-900 hover:bg-purple-50"
                onClick={() => addStep("SEQUENTIAL")}
              >
                Sequential
              </button>
              <button
                type="button"
                className="block w-full rounded px-3 py-2 text-left text-sm text-ink-900 hover:bg-purple-50"
                onClick={() => addStep("GROUP")}
              >
                Group of activities
              </button>
            </div>
          ) : null}
        </div>
      </div>

      {canPickStatus ? (
        <Field label="Status">
          <Select value={status} onChange={(event) => setStatus(event.target.value)}>
            {statusChoices.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </Select>
        </Field>
      ) : (
        <div>
          <span className="field-label">Status</span>
          <div className="mt-1.5 flex items-center gap-2">
            {isEdit ? <StatusBadge status={String(initial?.status ?? "PENDING")} /> : <StatusBadge status="PENDING" />}
            <Lock className="h-3.5 w-3.5 text-ink-500" aria-hidden />
          </div>
          <p className="mt-1 text-xs text-ink-500">New records are reviewed by a professor or admin.</p>
        </div>
      )}

      <div className="rounded-xl bg-[#2A2520] p-3 text-xs text-[#E0C9B0]">
        Document processes yourself, even if someone else has — each perspective is kept.
      </div>

      {footerFields ? <div className="grid gap-3 border-t border-line-200 pt-4">{footerFields}</div> : null}

      <HeldPostNotice refusal={filing.notice} id={filingNoticeId} className="" sayPending />

      <div className="flex items-center justify-end gap-2">
        {uploadNote ? <span className="mr-auto text-xs text-ink-500">{uploadNote}</span> : null}
        <ActionGroup className="max-sm:w-full"><button type="button" className="field-button-secondary" onClick={requestCancel}>
          Cancel
        </button>
        <button
          className="field-button"
          disabled={saving || committed || filing.saveHeld}
          aria-describedby={filing.notice ? filingNoticeId : undefined}
          type="submit"
        >
          {saving ? "Saving..." : committed ? "Saved" : isEdit ? "Update process" : "Save process"}
        </button></ActionGroup>
      </div>

      <UnsavedChangesDialog
        open={guardOpen}
        saving={saving}
        onKeepEditing={() => {
          setGuardOpen(false);
          setPromptFromCancel(false);
        }}
        onDiscard={() => {
          setGuardOpen(false);
          setPromptFromCancel(false);
          if (promptFromCancel) onCancel();
          else leaveAfterDiscard();
        }}
        onSave={() => {
          setPromptFromCancel(false);
          void submit();
        }}
      />
    </form>
  );
}
