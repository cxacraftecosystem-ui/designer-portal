"use client";

import { byCount, countNoun } from "@/lib/format";
import { useEffect, useId, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { useAuth } from "@/components/AuthProvider";
import { statusLabel } from "@/components/StatusBadge";
import { mergeById } from "@/components/data/cappedList";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { Field, Select, TextInput } from "@/components/FormControls";
import { CarryContextBanner, carryScope, useCarryContext } from "@/components/forms/CarryContextBanner";
import type { CarryNode } from "@/lib/carryContext";
import { cmTextFromInches, inchesTextFromCm, propagate } from "@/components/forms/dimensionUnits";
import { LocationFields, type LocationInitialValues } from "@/components/forms/LocationFields";
import {
  forgetAcceptance,
  NO_ACCEPTED_MEASUREMENTS,
  rememberAcceptance,
  type AcceptedMeasurements
} from "@/components/forms/measurementMethods";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { seedHasArtisan, type InlineHostSeed, type InlineRecordSurfaceProps } from "@/components/forms/inlineRecordHost";
import {
  artisanPickerOptions,
  craftSelectionVerdict,
  craftsChangeClearsArtisans,
  joinCraftNames,
  useCraftAndArtisanOptions,
  useRecordsOffPage
} from "@/components/forms/recordPickers";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { MultiSelectDropdown } from "@/components/ui/Dropdown";
import { useWorkshopPicker, WorkshopPicker } from "@/components/forms/WorkshopPicker";
import { HeldPostNotice, useRecordFilingHold } from "@/components/designworkshop/HeldPostNotice";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { GridMeasurement, MEASUREMENT_GRID_PURPOSE, type GridFiles, type GridGroup } from "@/components/media/GridMeasurement";
import { RecordPhotoMeasure, type MeasureColumn } from "@/components/media/RecordPhotoMeasure";
import { UploadProgress } from "@/components/media/UploadProgress";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { RichTextField } from "@/components/richtext/RichTextField";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { apiFetch } from "@/lib/api";
import { locationFromForm, recordedAtFromForm, recordedTimezoneFromForm, useFocusField, useUnsavedChanges } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { collectExifMetadata, exifMetadataToRemark, inferMediaType, mediaKindWord, uploadMediaBatch, uploadMediaFile, type BatchProgress } from "@/lib/media";
import { recordingFilename, splitExtension } from "@/lib/naming";
import { saveOrQueue } from "@/lib/offline";
import { toolBody } from "@/lib/recordPayloads";
import { canManageCrafts, hasRank } from "@/lib/permissions";
import type { Artisan, Craft, RecordStatus, ToolDocumentation } from "@/lib/types";
import { CraftAddDraftBoundary } from "@/components/forms/CraftAddDraftBoundary";
import { CraftSelect } from "@/components/forms/CraftSelect";
import { MATERIAL_FAMILY_OPTIONS, TRADITION_OPTIONS, traditionChoice } from "@/lib/standardValues";
import { makerOptions } from "@/lib/types";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { LiveCompleteness } from "@/components/records/LiveCompleteness";

function stageStepFile(file: File, index: number, toolName: string | null | undefined): File {
  const name = recordingFilename("Tool", splitExtension(file.name)[1], {
    capturedAt: new Date(file.lastModified || Date.now()),
    subject: toolName,
    what: `Step-${index + 1}-${mediaKindWord(file)}`,
    sequence: "01"
  });
  return new File([file], name, { type: file.type, lastModified: file.lastModified });
}

function initialLinkIds(linked: string[] | undefined, fallback: string | null | undefined): string[] {
  const ids = [...(fallback ? [fallback] : []), ...(linked ?? [])];
  return ids.map((id) => id.trim()).filter((id, index, all) => Boolean(id) && all.indexOf(id) === index);
}

const MEASURE_COLUMNS: MeasureColumn[] = [
  { key: "lengthInches", label: "Length (inches)", unit: "in" },
  { key: "breadthInches", label: "Breadth (inches)", unit: "in" },
  { key: "heightInches", label: "Height (inches)", unit: "in" }
];

function StatusField({
  canSetStatus,
  initialStatus,
  onDirty
}: {
  canSetStatus: boolean;
  initialStatus?: RecordStatus;
  onDirty?: () => void;
}) {
  if (canSetStatus) {
    const options: RecordStatus[] = ["DRAFT", "PENDING", "APPROVED", "REJECTED"];
    if (initialStatus === "NEEDS_REVISION") options.push("NEEDS_REVISION");
    return (
      <Field label="Status">
        <Select name="status" defaultValue={initialStatus ?? "APPROVED"} onChange={onDirty}>
          {options.map((status) => (
            <option key={status} value={status}>
              {statusLabel(status)}
            </option>
          ))}
        </Select>
      </Field>
    );
  }
  const text = initialStatus ? initialStatus.charAt(0) + initialStatus.slice(1).toLowerCase().replace(/_/g, " ") : "Pending";
  return (
    <div className="grid content-start gap-1">
      <span className="field-label">Status</span>
      <span
        className="inline-flex h-10 w-fit items-center rounded-full border border-line-200 bg-surface-50 px-4 text-sm font-medium text-ink"
        title="Submitted for review — a reviewer sets the final status."
      >
        {text}
      </span>
    </div>
  );
}

export function ToolForm({
  initial,
  seed,
  footerFields,
  onCreated,
  onCancel,
  onDiscardAndLeave,
  onQueued
}: {
  initial?: ToolDocumentation;
  seed?: InlineHostSeed;
  onCancel?: () => void;
  onQueued?: () => void;
  onCreated?: (record: ToolDocumentation) => void;
} & InlineRecordSurfaceProps) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { user } = useAuth();
  const canSetStatus = hasRank(user, "PROFESSOR");
  const formRef = useRef<HTMLFormElement>(null);
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  useFocusField(true);
  const formId = useId();
  const errorId = `${formId}-error`;
  const craftLimitId = `${formId}-craft-limit`;
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  const [craftIds, setCraftIds] = useState<string[]>(() =>
    initialLinkIds(initial?.craftLinks?.map((link) => link.craftId), initial?.craftId ?? searchParams.get("craftId"))
  );
  const [artisanIds, setArtisanIds] = useState<string[]>(() =>
    initialLinkIds(
      initial?.artisanLinks?.map((link) => link.artisanId),
      initial?.artisanId ?? seed?.artisanId ?? searchParams.get("artisanId")
    )
  );
  const craftId = craftIds[0] ?? "";
  const artisanId = artisanIds[0] ?? "";
  const [craftLimitNotice, setCraftLimitNotice] = useState("");
  const [craftName, setCraftName] = useState(initial?.craftName ?? searchParams.get("craftName") ?? "");
  const [artisanName, setArtisanName] = useState(
    initial?.artisanName ?? seed?.artisanName ?? searchParams.get("artisanName") ?? ""
  );
  const [place, setPlace] = useState(initial?.place ?? searchParams.get("place") ?? "");
  const [toolkitName, setToolkitName] = useState(initial?.toolkitName ?? "");
  const [localName, setLocalName] = useState(initial?.localName ?? "");
  const [englishName, setEnglishName] = useState(initial?.englishName ?? "");
  const mirrorArmed = useRef(
    !initial || (initial.englishName ?? "").trim() === "" || (initial.englishName ?? "") === (initial.toolkitName ?? "")
  );
  const [processUsedIn, setProcessUsedIn] = useState(initial?.processUsedIn ?? "");
  const [material, setMaterial] = useState(initial?.material ?? "");
  const [stageFiles, setStageFiles] = useState<File[]>([]);
  const [length, setLength] = useState(initial?.lengthInches != null ? String(initial.lengthInches) : "");
  const [breadth, setBreadth] = useState(initial?.breadthInches != null ? String(initial.breadthInches) : "");
  const [height, setHeight] = useState(initial?.height != null ? String(initial.height) : "");
  const [width, setWidth] = useState(initial?.width != null ? String(initial.width) : "");
  const [heightInches, setHeightInches] = useState(initial?.heightInches != null ? String(initial.heightInches) : "");
  const [accepted, setAccepted] = useState<AcceptedMeasurements>(NO_ACCEPTED_MEASUREMENTS);
  const [gridFiles, setGridFiles] = useState<GridFiles>({});
  const [measurePhoto, setMeasurePhoto] = useState<{ file: File; isGrid: boolean } | null>(null);
  const embeddedCrafts = useMemo(
    () => (initial?.craftLinks ?? []).map((link) => link.craft).filter((craft): craft is Craft => Boolean(craft)),
    [initial?.craftLinks]
  );
  const embeddedArtisans = useMemo(
    () =>
      (initial?.artisanLinks ?? [])
        .map((link) => link.artisan)
        .filter((artisan): artisan is Artisan => Boolean(artisan)),
    [initial?.artisanLinks]
  );
  const {
    artisans,
    crafts,
    referenceState,
    craftArtisanCut,
    craftRosterKey,
    artisansLoadedForCraft,
    craftArtisanNotice,
    craftArtisanEmptyLabel
  } = useCraftAndArtisanOptions({ craftIds, artisanIds, knownArtisans: embeddedArtisans });
  const offPageCrafts = useRecordsOffPage<Craft>("/crafts", craftIds, crafts);
  const craftOptions = useMemo(() => {
    const loaded = offPageCrafts.length ? mergeById(crafts, offPageCrafts) : crafts;
    return embeddedCrafts.length ? mergeById(loaded, embeddedCrafts) : loaded;
  }, [crafts, offPageCrafts, embeddedCrafts]);
  const craftFallbackLabels = useMemo(
    () => Object.fromEntries(embeddedCrafts.map((craft) => [craft.id, craft.name])),
    [embeddedCrafts]
  );
  const selectedCrafts = useMemo(
    () => craftIds.map((id) => craftOptions.find((craft) => craft.id === id)).filter((craft): craft is Craft => Boolean(craft)),
    [craftIds, craftOptions]
  );
  const { dirty: typedSinceMount, markDirty, resetDirty } = useUnsavedChanges();
  const [backPromptOpen, setBackPromptOpen] = useState(false);
  const [promptFromCancel, setPromptFromCancel] = useState(false);
  const dirty = typedSinceMount;
  useLeaveGuard(dirty, () => setBackPromptOpen(true));
  const initialLocation = initial
    ? ((initial as ToolDocumentation & { location?: LocationInitialValues | null }).location ?? null)
    : undefined;
  const isEdit = Boolean(initial);
  const workshop = useWorkshopPicker({
    initialWorkshopId: initial?.workshopId ?? seed?.workshopId,
    initialDesignWorkshopId: initial?.designWorkshopId,
    isEdit,
    resetKey: initial?.id ?? null
  });
  const filing = useRecordFilingHold(initial?.designWorkshopId, workshop.designWorkshopId, "tool");
  const filingNoticeId = useId();

  useEffect(() => {
    if (isEdit || !artisanId) return;
    const known = artisans.find((artisan) => artisan.id === artisanId);
    if (!known) return;
    if (known.craftId) setCraftIds((current) => (current.length ? current : [known.craftId as string]));
    if (known.craft?.name) setCraftName((current) => current || known.craft?.name || "");
    if (known.name) setArtisanName((current) => current || known.name);
    if (known.place) setPlace((current) => current || known.place);
  }, [artisans, artisanId, isEdit]);

  const craftNameReconciled = useRef(!initial);
  useEffect(() => {
    if (craftNameReconciled.current) return;
    if (!craftIds.length || typedSinceMount) {
      craftNameReconciled.current = true;
      return;
    }
    if (!craftIds.every((id) => craftOptions.some((craft) => craft.id === id))) return;
    craftNameReconciled.current = true;
    const joined = joinCraftNames(craftIds, craftOptions);
    setCraftName((current) => (joined && joined !== current ? joined : current));
  }, [craftIds, craftOptions, typedSinceMount]);

  const typeInches =
    (set: (value: string) => void, key: string, setPartner?: (value: string) => void) =>
    (event: React.ChangeEvent<HTMLInputElement>) => {
      set(event.target.value);
      setAccepted((current) => forgetAcceptance(current, key));
      if (setPartner) propagate(event.target.value, cmTextFromInches, setPartner);
    };

  const typeCm =
    (set: (value: string) => void, setPartner: (value: string) => void) => (event: React.ChangeEvent<HTMLInputElement>) => {
      set(event.target.value);
      propagate(event.target.value, inchesTextFromCm, setPartner);
    };

  function applyToolkitName(next: string, { user }: { user: boolean }) {
    setToolkitName(next);
    if (user) markDirty();
    if (mirrorArmed.current) setEnglishName(next);
  }

  const carryApplies = useMemo<CarryNode[]>(() => {
    const nodes: CarryNode[] = [];
    if (!seedHasArtisan(seed)) nodes.push("craft", "artisan");
    if (!seed?.workshopId) nodes.push("workshop");
    return nodes;
  }, [seed]);

  const carry = useCarryContext({
    enabled: !isEdit,
    scopes: [carryScope("artisan", referenceState, artisans), carryScope("craft", referenceState, craftOptions)],
    applies: carryApplies,
    onApply: (context) => {
      if (context.craftId) {
        setCraftIds([context.craftId]);
        setCraftLimitNotice("");
      }
      if (context.craftName) setCraftName(context.craftName);
      if (context.artisanId) setArtisanIds([context.artisanId]);
      if (context.artisanName) setArtisanName(context.artisanName);
      if (context.place) setPlace(context.place);
      if (context.workshopId && !workshop.touched) workshop.setWorkshopId(context.workshopId);
    }
  });

  function clearCarriedContext() {
    carry.change();
    setCraftIds([]);
    setCraftLimitNotice("");
    setCraftName("");
    setArtisanIds([]);
    setArtisanName("");
    setPlace("");
  }

  const artisansForCraft = craftIds.length
    ? artisans.filter(
        (artisan) => (artisan.craftId && craftIds.includes(artisan.craftId)) || artisanIds.includes(artisan.id)
      )
    : artisans;

  function applyFirstArtisan(next: readonly string[]): Artisan | undefined {
    const first = next[0] ?? "";
    const artisan = first ? artisans.find((candidate) => candidate.id === first) : undefined;
    if (artisan && first !== artisanId) {
      setArtisanName(artisan.name);
      setPlace(artisan.place);
    }
    return artisan;
  }

  function sittingCraftName(fallback: string): string {
    const named = craftId ? craftOptions.find((craft) => craft.id === craftId)?.name : null;
    if (named) return named;
    return craftIds.length > 1 ? "" : fallback;
  }

  function onCraftsChanged(next: string[]) {
    const verdict = craftSelectionVerdict({ nextCraftIds: next, currentCraftIds: craftIds, crafts: craftOptions });
    setCraftLimitNotice(verdict.notice);
    if (verdict.refuse) return;
    const removed = craftIds.filter((id) => !next.includes(id));
    const dropped = craftsChangeClearsArtisans({
      nextCraftIds: next,
      removedCraftIds: removed,
      artisanIds,
      artisans
    });
    if (dropped.length) {
      const remaining = artisanIds.filter((id) => !dropped.includes(id));
      applyFirstArtisan(remaining);
      setArtisanIds(remaining);
    }
    setCraftIds(next);
    const joined = joinCraftNames(next, craftOptions);
    if (joined) setCraftName(joined);
    markDirty();
  }

  function onArtisansChanged(next: string[]) {
    const artisan = applyFirstArtisan(next);
    if (artisan) {
      carry.remember(
        {
          artisanId: artisan.id,
          artisanName: artisan.name,
          place: artisan.place,
          craftId,
          craftName: sittingCraftName(craftName)
        },
        { explicit: true }
      );
    }
    setArtisanIds(next);
    markDirty();
  }

  function leave() {
    if (onCancel) onCancel();
    else router.back();
  }

  function leaveAfterDiscard() {
    if (onDiscardAndLeave) onDiscardAndLeave();
    else leave();
  }

  function handleBack() {
    if (dirty) {
      setPromptFromCancel(true);
      setBackPromptOpen(true);
    } else leave();
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    if (filing.saveHeld) return;
    if (!(await workshop.confirmSubmission())) return;
    setSaving(true);
    setError(null);
    try {
      const exifItems = await collectExifMetadata(
        [...Object.values(gridFiles), measurePhoto?.file, ...stageFiles, ...mediaFiles].filter(Boolean) as File[]
      );
      const exifRemark = exifMetadataToRemark(exifItems);
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      const location = locationFromForm(form);
      const payload = toolBody(form, {
        craftName: craftName || joinCraftNames(craftIds, craftOptions),
        craftIds,
        artisanIds,
        dimensions: { height, width, length, breadth, heightInches },
        accepted,
        workshopId: workshop.workshopId || null,
        designWorkshopId: workshop.designWorkshopId || null,
        initialStatus: initial?.status,
        exif: { items: exifItems, remark: exifRemark }
      });
      const outcome = await saveOrQueue<ToolDocumentation>({
        label: `Tool · ${payload.toolkitName || "Untitled"}`,
        endpoint: initial ? `/tools/${initial.id}` : "/tools",
        method: initial ? "PATCH" : "POST",
        body: payload,
        media: [
          ...(Object.entries(gridFiles) as [GridGroup, File][]).map(([group, file]) => ({
            files: [file],
            linkedRecordType: "tool",
            caption: `${group === "lengthBreadth" ? "Length & breadth" : "Height"} grid (measurement) for ${payload.toolkitName || "tool"}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: { purpose: MEASUREMENT_GRID_PURPOSE },
          })),
          ...stageFiles.map((file, index) => ({
            files: [stageStepFile(file, index, payload.toolkitName)],
            linkedRecordType: "tool",
            caption: `Process stage step ${index + 1} for ${payload.toolkitName || "tool"}`,
            location,
            recordedAt,
            recordedTimezone,
          })),
          {
            files: mediaFiles,
            linkedRecordType: "tool",
            caption: `Field media for ${payload.toolkitName || "tool"}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined
          },
          ...(measurePhoto
            ? [
                {
                  files: [measurePhoto.file],
                  linkedRecordType: "tool",
                  caption: `Measured from this photograph — ${payload.toolkitName || "tool"}`,
                  location,
                  recordedAt,
                  recordedTimezone,
                  extraMetadata: measurePhoto.isGrid ? { purpose: MEASUREMENT_GRID_PURPOSE } : undefined,
                }
              ]
            : [])
        ]
      });
      const sitting = {
        artisanId,
        artisanName: payload.artisanName,
        place: payload.place,
        craftId,
        craftName: sittingCraftName(payload.craftName),
        workshopId: workshop.workshopId,
        workshopName: workshop.workshops.find((w) => w.id === workshop.workshopId)?.title ?? null
      };
      if (outcome.queued) {
        carry.prune("tool");
        carry.remember(sitting);
        resetDirty();
        setSaving(false);
        if (onQueued) {
          onQueued();
          return;
        }
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        return;
      }
      const saved = outcome.saved;
      carry.remember({ ...sitting, toolId: saved.id, toolName: saved.toolkitName });
      for (const [group, file] of Object.entries(gridFiles) as [GridGroup, File][]) {
        try {
          await uploadMediaFile({
            file,
            linkedRecordType: "tool",
            linkedRecordId: saved.id,
            caption: `${group === "lengthBreadth" ? "Length & breadth" : "Height"} grid (measurement) for ${saved.toolkitName}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: { purpose: MEASUREMENT_GRID_PURPOSE },
          });
        } catch {
        }
      }
      if (measurePhoto) {
        try {
          await uploadMediaFile({
            file: measurePhoto.file,
            linkedRecordType: "tool",
            linkedRecordId: saved.id,
            caption: `Measured from this photograph — ${saved.toolkitName}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: measurePhoto.isGrid ? { purpose: MEASUREMENT_GRID_PURPOSE } : undefined,
          });
        } catch {
        }
      }
      const stageFailed: string[] = [];
      for (const [index, file] of stageFiles.entries()) {
        try {
          await uploadMediaFile({
            file: stageStepFile(file, index, saved.toolkitName),
            linkedRecordType: "tool",
            linkedRecordId: saved.id,
            caption: `Process stage step ${index + 1} for ${saved.toolkitName}`,
            location,
            recordedAt,
            recordedTimezone
          });
        } catch {
          stageFailed.push(file.name);
        }
      }
      if (stageFailed.length) {
        setError(
          `${countNoun(stageFailed.length, "process stage file")} failed to upload: ${stageFailed.join(", ")}. ` +
            `The tool record was saved; re-open it to retry ${byCount(stageFailed.length, "that file", "those files")}.`
        );
        setSaving(false);
        if (onCreated) onCreated(saved);
        return;
      }
      if (mediaFiles.length) {
        const { failed } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "tool",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.toolkitName}`,
          location,
          recordedAt,
          recordedTimezone,
          extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined,
          onProgress: setUploadProgress
        });
        setUploadProgress(null);
        if (failed.length) {
          setError(`Saved, but these didn't upload: ${failed.map((f) => f.name).join(", ")}. Reopen to retry.`);
          setSaving(false);
          if (onCreated) onCreated(saved);
          return;
        }
      }
      resetDirty();
      if (onCreated) {
        onCreated(saved);
        return;
      }
      router.push("/tools");
      router.refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save tool record");
    } finally {
      setSaving(false);
      setUploadProgress(null);
    }
  }

  return (
    <>
      <form ref={formRef} onSubmit={submit} onInput={markDirty} onKeyDown={handleFormEnter} className="panel grid gap-4 p-4">
        {error ? (
          <div id={errorId} role="alert" className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
            {error}
          </div>
        ) : null}
        <CarryContextBanner offer={carry.applied} onChange={clearCarriedContext} />
        <LiveCompleteness
          kind="tool"
          formRef={formRef}
          stored={initial ?? null}
          values={{ artisanId: artisanId || null }}
          counts={{ photos: [...stageFiles, ...mediaFiles].filter((file) => inferMediaType(file) === "IMAGE").length }}
        />
        <DictationUnavailableNotice />
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          <WorkshopPicker state={workshop} onDirty={markDirty} saving={saving} disabled={filing.recordHeld} />
          <DictatedTextInput
            name="toolkitName"
            label="Toolkit name"
            required
            titleCased
            explainWhenUnavailable={false}
            value={toolkitName}
            onChange={(next) => applyToolkitName(next, { user: true })}
          />
          <div data-field="localName" className="grid min-w-0">
            <DictatedTextInput
              name="localName"
              label="Local name"
              explainWhenUnavailable={false}
              value={localName}
              onChange={(next) => {
                setLocalName(next);
                markDirty();
              }}
            />
          </div>
          <DictatedTextInput
            name="englishName"
            label="English name"
            titleCased
            explainWhenUnavailable={false}
            value={englishName}
            onChange={(next) => {
              setEnglishName(next);
              mirrorArmed.current = false;
              markDirty();
            }}
          />
          <FieldBlock label="Linked crafts" required>
            <CraftAddDraftBoundary
              formRef={formRef}
              onLeave={resetDirty}
              onCraftAdded={(addedId) => {
                if (craftIds.includes(addedId)) return;
                onCraftsChanged([...craftIds, addedId]);
                markDirty();
              }}
            >
              <CraftSelect
                multiple
                required
                ariaLabel="Linked crafts"
                value={craftIds}
                onChange={(ids) => onCraftsChanged(ids)}
                describedBy={craftLimitNotice ? craftLimitId : undefined}
                fallbackLabels={craftFallbackLabels}
                canAddCraft={canManageCrafts(user)}
              />
            </CraftAddDraftBoundary>
            {craftLimitNotice ? (
              <p id={craftLimitId} role="alert" className="mt-1 text-xs leading-5 text-error-600">
                {craftLimitNotice}
              </p>
            ) : null}
          </FieldBlock>
          <FieldBlock label="Linked artisans (fills artisan + place)" fieldKey="artisan">
            <MultiSelectDropdown
              values={artisanIds}
              onChange={onArtisansChanged}
              searchable
              placeholder={craftIds.length ? "Select linked artisans" : "Select a linked craft first"}
              emptyLabel={craftArtisanEmptyLabel || undefined}
              options={artisanPickerOptions(artisansForCraft, selectedCrafts)}
              disabled={craftIds.length === 0}
            />
            {craftIds.length && artisansLoadedForCraft === craftRosterKey && artisansForCraft.length === 0 ? (
              <p className="mt-1 text-xs text-ink-muted">
                No artisans are linked to {craftIds.length > 1 ? "these crafts" : "this craft"} yet.
              </p>
            ) : null}
            <CappedListNotice cuts={[craftIds.length ? craftArtisanCut : null]} />
            {craftArtisanNotice ? <p className="mt-1 text-xs leading-5 text-ink-500">{craftArtisanNotice}</p> : null}
          </FieldBlock>
          <DictatedTextInput
            name="artisanName"
            label="Artisan name"
            required
            titleCased
            explainWhenUnavailable={false}
            value={artisanName}
            onChange={(next) => {
              setArtisanName(next);
              markDirty();
            }}
          />
          <DictatedTextInput
            name="place"
            label="Place"
            required
            titleCased
            explainWhenUnavailable={false}
            value={place}
            onChange={(next) => {
              setPlace(next);
              markDirty();
            }}
          />
          <DictatedTextInput
            name="processUsedIn"
            label="Process used in"
            explainWhenUnavailable={false}
            value={processUsedIn}
            onChange={(next) => {
              setProcessUsedIn(next);
              markDirty();
            }}
          />
          <div data-field="material" className="grid min-w-0">
            <DictatedTextInput
              name="material"
              label="Material"
              explainWhenUnavailable={false}
              value={material}
              onChange={(next) => {
                setMaterial(next);
                markDirty();
              }}
            />
          </div>
          <Field label="Material type">
            <Select name="materialFamily" defaultValue={initial?.materialFamily ?? ""} onChange={markDirty}>
              <option value="">Select material type</option>
              {MATERIAL_FAMILY_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Years in use">
            <TextInput name="yearsInUse" type="number" min={0} defaultValue={initial?.yearsInUse ?? ""} />
          </Field>
          <Field label="Height (cm)">
            <TextInput name="height" type="number" min={0} step="0.01" aria-describedby={`${formId}-heights`} value={height} onChange={typeCm(setHeight, setHeightInches)} />
          </Field>
          <Field label="Width (cm)">
            <TextInput name="width" type="number" min={0} step="0.01" value={width} onChange={typeCm(setWidth, setBreadth)} />
          </Field>
          <Field label="Length (inches)" fieldKey="dimensions">
            <TextInput name="lengthInches" type="number" min={0} step="0.01" value={length} onChange={typeInches(setLength, "lengthInches")} />
          </Field>
          <Field label="Breadth (inches)">
            <TextInput name="breadthInches" type="number" min={0} step="0.01" value={breadth} onChange={typeInches(setBreadth, "breadthInches", setWidth)} />
          </Field>
          <Field label="Height (inches)">
            <TextInput name="heightInches" type="number" min={0} step="0.01" aria-describedby={`${formId}-heights`} value={heightInches} onChange={typeInches(setHeightInches, "heightInches", setHeight)} />
          </Field>
          <p id={`${formId}-heights`} className="text-xs leading-5 text-ink-500 md:col-span-2 xl:col-span-3">
            <strong>Height (cm)</strong> and <strong>Height (inches)</strong> are the same measurement in two units, and
            filling either fills the other (1&nbsp;inch = 2.54&nbsp;cm, rounded to two decimals).{" "}
            <strong>Width (cm)</strong> and <strong>Breadth (inches)</strong> pair the same way.{" "}
            <strong>Length (inches)</strong> has no centimetre box. If two paired boxes disagree, correct whichever is
            wrong and its partner follows.
          </p>
          <Field label="Thickness">
            <TextInput name="thickness" type="number" min={0} step="0.01" defaultValue={initial?.thickness ?? ""} />
          </Field>
          <Field label="Weight">
            <TextInput name="weight" type="number" min={0} step="0.01" defaultValue={initial?.weight ?? ""} />
          </Field>
          <Field label="Radius">
            <TextInput name="radius" type="number" min={0} step="0.01" defaultValue={initial?.radius ?? ""} />
          </Field>
        </div>
        <RecordPhotoMeasure
          columns={MEASURE_COLUMNS}
          values={{ lengthInches: length, breadthInches: breadth, heightInches }}
          onPropose={(key, text, method) => {
            if (key === "lengthInches") setLength(text);
            else if (key === "breadthInches") {
              setBreadth(text);
              propagate(text, cmTextFromInches, setWidth);
            }
            else if (key === "heightInches") {
              setHeightInches(text);
              propagate(text, cmTextFromInches, setHeight);
            }
            setAccepted((current) => rememberAcceptance(current, key, text, method));
            markDirty();
          }}
          onPhotoChange={(photo) => {
            setMeasurePhoto(photo);
            if (photo) markDirty();
          }}
        />
        <section className="grid gap-2 rounded-lg border border-line-200 bg-card p-4">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-sm font-semibold text-ink-900">If you cannot mark it: let AI estimate it</h3>
            <span className="rounded-full border border-amber-500 bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
              Needs a connection
            </span>
          </div>
          <p className="text-xs leading-5 text-ink-500">
            An AI <strong>estimate</strong>. Prefer the panel above when a grid or ruler is in the photo.
          </p>
          <GridMeasurement
            includeHeight
            onLengthBreadth={(l, b, method) => {
              if (l) {
                setLength(l);
                setAccepted((current) => rememberAcceptance(current, "lengthInches", l, method));
              }
              if (b) {
                setBreadth(b);
                propagate(b, cmTextFromInches, setWidth);
                setAccepted((current) => rememberAcceptance(current, "breadthInches", b, method));
              }
              markDirty();
            }}
            onHeight={(value, method) => {
              setHeightInches(value);
              propagate(value, cmTextFromInches, setHeight);
              setAccepted((current) => rememberAcceptance(current, "heightInches", value, method));
              markDirty();
            }}
            onFilesChange={(files) => {
              setGridFiles(files);
              markDirty();
            }}
          />
        </section>
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          <Field label="Maker" fieldKey="maker">
            <Select name="maker" defaultValue={initial?.maker ?? "UNKNOWN"} onChange={markDirty}>
              {makerOptions.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
          <Field label="Tradition type" fieldKey="traditionType">
            <Select name="traditionType" defaultValue={traditionChoice(initial?.traditionType)} onChange={markDirty}>
              <option value="">Select tradition type</option>
              {TRADITION_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Replacement cost" fieldKey="replacementCost">
            <TextInput name="replacementCost" type="number" min={0} step="0.01" defaultValue={initial?.replacementCost ?? ""} />
          </Field>
        </div>
        <div className="grid gap-3 md:grid-cols-2">
          <RichTextField
            name="suggestionsForToolImprovement"
            label="Suggestions for improvement"
            defaultValue={initial?.suggestionsForToolImprovement ?? ""}
            className="md:col-span-2"
            onDirty={markDirty}
            explainWhenUnavailable={false}
          />
          <RichTextField
            name="remarks"
            label="Remarks"
            defaultValue={initial?.remarks ?? ""}
            className="md:col-span-2"
            onDirty={markDirty}
            explainWhenUnavailable={false}
          />
          <StatusField canSetStatus={canSetStatus} initialStatus={initial?.status} onDirty={markDirty} />
        </div>
        <MediaCaptureField
          files={stageFiles}
          onFilesChange={(files) => {
            setStageFiles(files);
            markDirty();
          }}
          title="Process stages"
          description="Document each step of making or using this tool, in order."
        />
        {initial ? <ExistingMedia linkedRecordType="tool" linkedRecordId={initial.id} recordHold={filing.stored} /> : null}
        <div data-field="photo" className="grid min-w-0">
          <MediaCaptureField
            files={mediaFiles}
            onFilesChange={(files) => {
              setMediaFiles(files);
              markDirty();
            }}
            title="Tool media"
            description="Photos, video, audio and documents."
          />
        </div>
        <LocationFields initial={initialLocation} onDirty={markDirty} />
        {uploadProgress ? <UploadProgress progress={uploadProgress} files={[...stageFiles, ...mediaFiles]} /> : null}
        {footerFields ? <div className="grid gap-3 border-t border-line-200 pt-4">{footerFields}</div> : null}
        <HeldPostNotice refusal={filing.notice} id={filingNoticeId} className="" sayPending />
        <ActionGroup align="end">
          <button type="button" className="field-button-secondary" onClick={handleBack}>
            Cancel
          </button>
          <button
            className="field-button"
            disabled={saving || filing.saveHeld}
            aria-describedby={filing.notice ? filingNoticeId : undefined}
          >
            {saving ? "Saving..." : initial ? "Update tool" : "Save tool"}
          </button>
        </ActionGroup>
      </form>
      <UnsavedChangesDialog
        open={backPromptOpen}
        saving={saving}
        onKeepEditing={() => {
          setBackPromptOpen(false);
          setPromptFromCancel(false);
        }}
        onDiscard={() => {
          setBackPromptOpen(false);
          setPromptFromCancel(false);
          resetDirty();
          if (promptFromCancel) leave();
          else leaveAfterDiscard();
        }}
        onSave={() => {
          setBackPromptOpen(false);
          setPromptFromCancel(false);
          formRef.current?.requestSubmit();
        }}
      />
    </>
  );
}
