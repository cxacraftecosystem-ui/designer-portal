"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { useAuth } from "@/components/AuthProvider";
import { statusLabel } from "@/components/StatusBadge";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { Field, Select, TextInput } from "@/components/FormControls";
import { CarryContextBanner, carryScope, useCarryContext } from "@/components/forms/CarryContextBanner";
import type { CarryNode } from "@/lib/carryContext";
import { LocationFields, type LocationInitialValues } from "@/components/forms/LocationFields";
import {
  forgetAcceptance,
  measurementMethodsFor,
  NO_ACCEPTED_MEASUREMENTS,
  rememberAcceptance,
  type AcceptedMeasurements
} from "@/components/forms/measurementMethods";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { seedHasArtisan, type InlineHostSeed, type InlineRecordSurfaceProps } from "@/components/forms/inlineRecordHost";
import { craftChangeClearsArtisan, useCraftAndArtisanOptions } from "@/components/forms/recordPickers";
import { CraftAddDraftBoundary } from "@/components/forms/CraftAddDraftBoundary";
import { CraftSelect } from "@/components/forms/CraftSelect";
import { CRAFT_NOT_IN_LIST } from "@/lib/craftCatalogue";
import { canManageCrafts } from "@/lib/permissions";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { useWorkshopPicker, WorkshopPicker } from "@/components/forms/WorkshopPicker";
import { HeldPostNotice, useRecordFilingHold } from "@/components/designworkshop/HeldPostNotice";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { GridMeasurement, MEASUREMENT_GRID_PURPOSE, type GridFiles, type GridGroup } from "@/components/media/GridMeasurement";
import { RecordPhotoMeasure, type MeasureColumn } from "@/components/media/RecordPhotoMeasure";
import { UploadProgress } from "@/components/media/UploadProgress";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { RichTextField } from "@/components/richtext/RichTextField";
import { appendStoredParagraph } from "@/components/richtext/storedRichText";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { apiFetch } from "@/lib/api";
import { locationFromForm, numericValue, recordedAtFromForm, recordedTimezoneFromForm, requiredText, textValue, useFocusField, useUnsavedChanges } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { collectExifMetadata, exifMetadataToRemark, inferMediaType, uploadMediaBatch, uploadMediaFile, type BatchProgress } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { hasRank } from "@/lib/permissions";
import type { Artisan, ProductDocumentation, RecordStatus } from "@/lib/types";
import { marketDemandOptions, productTypes } from "@/lib/types";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { LiveCompleteness } from "@/components/records/LiveCompleteness";

function artisanOptionLabel(artisan: Artisan) {
  const name = artisan.name?.trim() || "Unnamed artisan";
  return artisan.place?.trim() ? `${name} · ${artisan.place.trim()}` : name;
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

export function ProductForm({
  initial,
  seed,
  footerFields,
  onCreated,
  onCancel,
  onDiscardAndLeave,
  onQueued
}: {
  initial?: ProductDocumentation;
  seed?: InlineHostSeed;
  onCancel?: () => void;
  onQueued?: () => void;
  onCreated?: (record: ProductDocumentation) => void;
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
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  const [craftId, setCraftId] = useState(initial?.craftId ?? searchParams.get("craftId") ?? "");
  const [artisanId, setArtisanId] = useState(initial?.artisanId ?? seed?.artisanId ?? searchParams.get("artisanId") ?? "");
  const [craftName, setCraftName] = useState(initial?.craftName ?? searchParams.get("craftName") ?? "");
  const [artisanName, setArtisanName] = useState(
    initial?.artisanName ?? seed?.artisanName ?? searchParams.get("artisanName") ?? ""
  );
  const [place, setPlace] = useState(initial?.place ?? searchParams.get("place") ?? "");
  const [productName, setProductName] = useState(initial?.productName ?? "");
  const [localName, setLocalName] = useState(initial?.localName ?? "");
  const [timeTaken, setTimeTaken] = useState(initial?.timeTakenToCompleteProduct ?? "");
  const [size, setSize] = useState(initial?.size ?? "");
  const [length, setLength] = useState(initial?.lengthInches != null ? String(initial.lengthInches) : "");
  const [breadth, setBreadth] = useState(initial?.breadthInches != null ? String(initial.breadthInches) : "");
  const [height, setHeight] = useState(initial?.heightInches != null ? String(initial.heightInches) : "");
  const [accepted, setAccepted] = useState<AcceptedMeasurements>(NO_ACCEPTED_MEASUREMENTS);
  const [gridFiles, setGridFiles] = useState<GridFiles>({});
  const [measurePhoto, setMeasurePhoto] = useState<{ file: File; isGrid: boolean } | null>(null);
  const {
    artisans,
    crafts,
    referenceState,
    craftArtisanCut,
    artisansLoadedForCraft,
    craftArtisanNotice,
    craftArtisanEmptyLabel
  } = useCraftAndArtisanOptions({ craftId, artisanId });
  const craftOptions = crafts;
  const craftFallbackLabels = useMemo(
    () => (initial?.craftId && initial.craftName ? { [initial.craftId]: initial.craftName } : undefined),
    [initial]
  );
  const { dirty: typedSinceMount, markDirty, resetDirty } = useUnsavedChanges();
  const [backPromptOpen, setBackPromptOpen] = useState(false);
  const [promptFromCancel, setPromptFromCancel] = useState(false);
  const dirty = typedSinceMount;
  useLeaveGuard(dirty, () => setBackPromptOpen(true));
  const initialLocation = initial
    ? ((initial as ProductDocumentation & { location?: LocationInitialValues | null }).location ?? null)
    : undefined;
  const isEdit = Boolean(initial);
  const workshop = useWorkshopPicker({
    initialWorkshopId: initial?.workshopId ?? seed?.workshopId,
    initialDesignWorkshopId: initial?.designWorkshopId,
    isEdit,
    resetKey: initial?.id ?? null
  });
  const filing = useRecordFilingHold(initial?.designWorkshopId, workshop.designWorkshopId, "product");
  const filingNoticeId = useId();

  useEffect(() => {
    if (isEdit || !artisanId) return;
    const known = artisans.find((artisan) => artisan.id === artisanId);
    if (!known) return;
    if (known.craftId) setCraftId((current) => current || known.craftId || "");
    if (known.craft?.name) setCraftName((current) => current || known.craft?.name || "");
    if (known.name) setArtisanName((current) => current || known.name);
    if (known.place) setPlace((current) => current || known.place);
  }, [artisans, artisanId, isEdit]);

  const toNum = (value: string) => {
    const n = Number(value);
    return value.trim() && Number.isFinite(n) ? n : null;
  };

  const typeInto =
    (set: (value: string) => void, key: string) => (event: React.ChangeEvent<HTMLInputElement>) => {
      set(event.target.value);
      setAccepted((current) => forgetAcceptance(current, key));
    };

  const artisansForCraft = craftId
    ? artisans.filter((artisan) => artisan.craftId === craftId || artisan.id === artisanId)
    : artisans;

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
      if (context.craftId) setCraftId(context.craftId);
      if (context.craftName) setCraftName(context.craftName);
      if (context.artisanId) setArtisanId(context.artisanId);
      if (context.artisanName) setArtisanName(context.artisanName);
      if (context.place) setPlace(context.place);
      if (context.workshopId && !workshop.touched) workshop.setWorkshopId(context.workshopId);
    }
  });

  function clearCarriedContext() {
    carry.change();
    setCraftId("");
    setCraftName("");
    setArtisanId("");
    setArtisanName("");
    setPlace("");
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
    if (!craftId) {
      setError(CRAFT_NOT_IN_LIST);
      return;
    }
    if (!(await workshop.confirmSubmission())) return;
    setSaving(true);
    setError(null);
    try {
      const exifItems = await collectExifMetadata(
        [...Object.values(gridFiles), measurePhoto?.file, ...mediaFiles].filter(Boolean) as File[]
      );
      const exifRemark = exifMetadataToRemark(exifItems);
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      const location = locationFromForm(form);
      const payload = {
        place: requiredText(form, "place"),
        artisanName: requiredText(form, "artisanName"),
        productName: requiredText(form, "productName"),
        localName: textValue(form, "localName"),
        productType: requiredText(form, "productType") || "OTHER",
        timeTakenToCompleteProduct: textValue(form, "timeTakenToCompleteProduct"),
        size: textValue(form, "size"),
        lengthInches: toNum(length),
        breadthInches: toNum(breadth),
        heightInches: toNum(height),
        measurementMethods: measurementMethodsFor(accepted, {
          lengthInches: length,
          breadthInches: breadth,
          heightInches: height
        }),
        costOfMaking: numericValue(form, "costOfMaking"),
        sellingPrice: numericValue(form, "sellingPrice"),
        marketDemand: requiredText(form, "marketDemand") || "UNKNOWN",
        rawMaterialsUsed: textValue(form, "rawMaterialsUsed"),
        mainToolsUsed: textValue(form, "mainToolsUsed"),
        productFunctionUse: textValue(form, "productFunctionUse"),
        remarks: appendStoredParagraph(textValue(form, "remarks") as string | null, exifRemark),
        artisanId: artisanId || null,
        craftId: craftId || null,
        workshopId: workshop.workshopId || null,
        designWorkshopId: workshop.designWorkshopId || null,
        status: requiredText(form, "status") || initial?.status || "PENDING",
        recordedAt,
        recordedTimezone,
        location,
        extraMetadata: exifItems.length ? { mediaExif: exifItems } : {}
      };
      const outcome = await saveOrQueue<ProductDocumentation>({
        label: `Product · ${payload.productName || "Untitled"}`,
        endpoint: initial ? `/products/${initial.id}` : "/products",
        method: initial ? "PATCH" : "POST",
        body: payload,
        media: [
          ...(Object.entries(gridFiles) as [GridGroup, File][]).map(([group, file]) => ({
            files: [file],
            linkedRecordType: "product",
            caption: `${group === "lengthBreadth" ? "Length & breadth" : "Height"} grid (measurement) for ${payload.productName || "product"}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: { purpose: MEASUREMENT_GRID_PURPOSE },
          })),
          {
            files: mediaFiles,
            linkedRecordType: "product",
            caption: `Field media for ${payload.productName || "product"}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined
          },
          ...(measurePhoto
            ? [
                {
                  files: [measurePhoto.file],
                  linkedRecordType: "product",
                  caption: `Measured from this photograph — ${payload.productName || "product"}`,
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
        craftName,
        workshopId: workshop.workshopId,
        workshopName: workshop.workshops.find((w) => w.id === workshop.workshopId)?.title ?? null
      };
      if (outcome.queued) {
        carry.prune("product");
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
      carry.remember({ ...sitting, productId: saved.id, productName: saved.productName });
      for (const [group, file] of Object.entries(gridFiles) as [GridGroup, File][]) {
        try {
          await uploadMediaFile({
            file,
            linkedRecordType: "product",
            linkedRecordId: saved.id,
            caption: `${group === "lengthBreadth" ? "Length & breadth" : "Height"} grid (measurement) for ${saved.productName}`,
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
            linkedRecordType: "product",
            linkedRecordId: saved.id,
            caption: `Measured from this photograph — ${saved.productName}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: measurePhoto.isGrid ? { purpose: MEASUREMENT_GRID_PURPOSE } : undefined,
          });
        } catch {
        }
      }
      if (mediaFiles.length) {
        const { failed } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "product",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.productName}`,
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
      router.push("/products");
      router.refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save product record");
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
          kind="product"
          formRef={formRef}
          stored={initial ?? null}
          counts={{ photos: mediaFiles.filter((file) => inferMediaType(file) === "IMAGE").length }}
        />
        <DictationUnavailableNotice />
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          <WorkshopPicker state={workshop} onDirty={markDirty} saving={saving} disabled={filing.recordHeld} />
          <DictatedTextInput
            name="productName"
            label="Product name"
            required
            titleCased
            explainWhenUnavailable={false}
            value={productName}
            onChange={(next) => {
              setProductName(next);
              markDirty();
            }}
          />
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
          <Field label="Product type">
            <Select name="productType" defaultValue={initial?.productType ?? "OTHER"} onChange={markDirty}>
              {productTypes.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
          <FieldBlock label="Craft" required fieldKey="craft">
            <CraftAddDraftBoundary
              formRef={formRef}
              onLeave={resetDirty}
              onCraftAdded={(addedId, craft) => {
                setCraftId(addedId);
                setCraftName(craft?.name ?? "");
                markDirty();
              }}
            >
              <CraftSelect
                name="craftId"
                required
                ariaLabel="Craft"
                value={craftId}
                fallbackLabels={craftFallbackLabels}
                canAddCraft={canManageCrafts(user)}
                onDirty={markDirty}
                onChange={(next, craft) => {
                  setCraftId(next);
                  setCraftName(craft?.name ?? "");
                  if (craftChangeClearsArtisan({ nextCraftId: next, artisanId, artisans })) {
                    setArtisanId("");
                  }
                }}
              />
            </CraftAddDraftBoundary>
          </FieldBlock>
          <Field label="Linked artisan (fills artisan + place)" fieldKey="artisan">
            <Select
              name="artisanId"
              searchable
              emptyLabel={craftArtisanEmptyLabel || undefined}
              value={artisanId}
              onChange={(event) => {
                const next = event.target.value;
                setArtisanId(next);
                const artisan = artisans.find((a) => a.id === next);
                if (artisan) {
                  setArtisanName(artisan.name);
                  setPlace(artisan.place);
                  carry.remember(
                    { artisanId: artisan.id, artisanName: artisan.name, place: artisan.place, craftId, craftName },
                    { explicit: true }
                  );
                }
                markDirty();
              }}
              disabled={!craftId}
            >
              <option value="">{craftId ? "Unlinked / type below" : "Select a linked craft first"}</option>
              {artisansForCraft.map((artisan) => (
                <option key={artisan.id} value={artisan.id}>
                  {artisanOptionLabel(artisan)}
                </option>
              ))}
            </Select>
            {craftId && artisansLoadedForCraft === craftId && artisansForCraft.length === 0 ? (
              <p className="mt-1 text-xs text-ink-muted">No artisans are linked to this craft yet.</p>
            ) : null}
            <CappedListNotice cuts={[craftId ? craftArtisanCut : null]} />
            {craftArtisanNotice ? <p className="mt-1 text-xs leading-5 text-ink-500">{craftArtisanNotice}</p> : null}
          </Field>
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
          <div data-field="timeTaken" className="grid min-w-0">
            <DictatedTextInput
              name="timeTakenToCompleteProduct"
              label="Time taken to complete"
              explainWhenUnavailable={false}
              value={timeTaken}
              onChange={(next) => {
                setTimeTaken(next);
                markDirty();
              }}
            />
          </div>
          <DictatedTextInput
            name="size"
            label="Size"
            explainWhenUnavailable={false}
            value={size}
            onChange={(next) => {
              setSize(next);
              markDirty();
            }}
          />
          <Field label="Length (inches)" fieldKey="dimensions">
            <TextInput name="lengthInches" type="number" min={0} step="0.01" value={length} onChange={typeInto(setLength, "lengthInches")} />
          </Field>
          <Field label="Breadth (inches)">
            <TextInput name="breadthInches" type="number" min={0} step="0.01" value={breadth} onChange={typeInto(setBreadth, "breadthInches")} />
          </Field>
          <Field label="Height (inches)">
            <TextInput name="heightInches" type="number" min={0} step="0.01" value={height} onChange={typeInto(setHeight, "heightInches")} />
          </Field>
        </div>
        <RecordPhotoMeasure
          columns={MEASURE_COLUMNS}
          values={{ lengthInches: length, breadthInches: breadth, heightInches: height }}
          onPropose={(key, text, method) => {
            if (key === "lengthInches") setLength(text);
            else if (key === "breadthInches") setBreadth(text);
            else if (key === "heightInches") setHeight(text);
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
                setAccepted((current) => rememberAcceptance(current, "breadthInches", b, method));
              }
              markDirty();
            }}
            onHeight={(value, method) => {
              setHeight(value);
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
          <Field label="Cost of making" fieldKey="costOfMaking">
            <TextInput name="costOfMaking" type="number" min={0} step="0.01" defaultValue={initial?.costOfMaking ?? ""} />
          </Field>
          <Field label="Selling price" fieldKey="sellingPrice">
            <TextInput name="sellingPrice" type="number" min={0} step="0.01" defaultValue={initial?.sellingPrice ?? ""} />
          </Field>
          <Field label="Market demand" fieldKey="marketDemand">
            <Select name="marketDemand" defaultValue={initial?.marketDemand ?? "UNKNOWN"} onChange={markDirty}>
              {marketDemandOptions.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
        </div>
        <div className="grid gap-3 md:grid-cols-2">
          <RichTextField
            name="rawMaterialsUsed"
            label="Raw materials used"
            defaultValue={initial?.rawMaterialsUsed ?? ""}
            className="md:col-span-2"
            onDirty={markDirty}
            explainWhenUnavailable={false}
          />
          <RichTextField
            name="mainToolsUsed"
            label="Main tools used"
            defaultValue={initial?.mainToolsUsed ?? ""}
            className="md:col-span-2"
            onDirty={markDirty}
            explainWhenUnavailable={false}
          />
          <RichTextField
            name="productFunctionUse"
            label="Function or use"
            defaultValue={initial?.productFunctionUse ?? ""}
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
        {initial ? (
          <ExistingMedia linkedRecordType="product" linkedRecordId={initial.id} recordHold={filing.stored} />
        ) : null}
        <div data-field="photo" className="grid min-w-0">
          <MediaCaptureField
            files={mediaFiles}
            onFilesChange={(files) => {
              setMediaFiles(files);
              markDirty();
            }}
            title="Product media"
            description="Photos, video, audio and documents."
          />
        </div>
        <LocationFields initial={initialLocation} onDirty={markDirty} />
        {uploadProgress ? <UploadProgress progress={uploadProgress} files={mediaFiles} /> : null}
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
            {saving ? "Saving..." : initial ? "Update product" : "Save product"}
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
