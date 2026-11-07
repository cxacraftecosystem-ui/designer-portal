"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  memo,
  useId,
  useInsertionEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore
} from "react";
import { deferredTool, registerWarm } from "@/components/designworkshop/deferredTool";
import type { DwFieldStamp } from "@/lib/designWorkshops";
import { FieldProvenance } from "@/components/designworkshop/FieldProvenance";
import { ImageOff, Paperclip, X } from "lucide-react";

import { DictationButton } from "@/components/designworkshop/Dictation";
import { InfoPanel, InfoToggle } from "@/components/ui/InfoDisclosure";
import { hasDetails } from "@/components/ui/infoToggleAria";
import { MediaAiVerbs } from "@/components/designworkshop/MediaAiVerbs";
import { MountWhenNear } from "@/components/designworkshop/MountWhenNear";
import { RichTextEditor } from "@/components/designworkshop/RichTextEditor";
import { fromStored, toPlain } from "@/lib/richText";
import { deriveValue, isDerived } from "@/lib/derivedFields";
import {
  addressListRole,
  dateRangePartner,
  identityNumberField,
  measurableLengthFields,
  offersIdentityOcr,
  offersPhotoMeasure,
  offersSignaturePad,
  offersSketchRectify,
  ownWorkshopTitleRole,
  recordMediaNoteRole,
  sketchSourceFields,
  workshopTitleRole
} from "@/components/designworkshop/stageFieldRoles";
import {
  fieldFormatError,
  formatShownByControl,
  isAadhaarMaskValue
} from "@/components/designworkshop/stageFieldFormats";
import type { SketchSource } from "@/components/designworkshop/SketchRectifyField";
import { SearchInput } from "@/components/SearchInput";
import { StageAddressField } from "@/components/designworkshop/StageAddressField";
import { StageGeoField } from "@/components/designworkshop/StageGeoField";
import { StageMediaNoteField } from "@/components/designworkshop/StageMediaNoteField";
import { StageWorkshopField } from "@/components/designworkshop/StageWorkshopField";
import { StageWorkshopNameField } from "@/components/designworkshop/StageWorkshopNameField";
import { StageReferenceSelect, scopeNoticeLines } from "@/components/designworkshop/StageReferenceField";
import { aadhaarValidationError, isMaskedIdentityNumber } from "@/components/forms/AadhaarField";
import { DateField, TimeField } from "@/components/forms/DateTimeField";
import { IdentityCardCapture } from "@/components/forms/IdentityCardCapture";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { DocumentPreview } from "@/components/media/DocumentPreview";
import { TranscriptViewer } from "@/components/transcript/TranscriptViewer";
import { DownloadMediaButton } from "@/components/media/DownloadMediaButton";
import { GalleryProgress } from "@/components/media/GalleryProgress";
import { MediaCarousel, type CarouselItem } from "@/components/media/MediaCarousel";
import { RefreshingImage } from "@/components/media/RefreshingImage";
import { capCeilingClause, mediaPickerTypeFilter } from "@/components/media/mediaPicker";
import { MediaRepositoryPicker } from "@/components/media/MediaRepositoryPicker";
import {
  declaredMinItems,
  galleryFloorSentence,
  gatePhotograph,
  gateRefusalSentence,
  gateScopeSentence,
  type CapturedFinding,
  type GateFault
} from "@/components/media/photoGate";
import { recordCaptureFindings } from "@/components/media/qualityFlagLog";
import { NumberedListField } from "@/components/forms/NumberedListInput";
import { PhoneField } from "@/components/forms/PhoneField";
import { Dropdown, MultiSelectDropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { apiFetch } from "@/lib/api";
import { fetchMediaRow } from "@/lib/mediaRows";
import {
  DW_DEFAULT_MAX_ITEMS,
  inputValue,
  listStageReferences,
  listValue,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwGeoValue,
  type DwReferenceOption,
  type DwReferencePayload,
  type DwValue
} from "@/lib/designWorkshops";
import {
  isLocalMediaRef,
  readLocalMedia,
  removeLocalMedia,
  stageLocalMedia,
  LOCAL_MEDIA_PREFIX
} from "@/lib/designWorkshopStore";
import { isMeasurableImage, measureImageFile } from "@/lib/imageQuality";
import {
  computeChecksum,
  getServerStagingSnapshot,
  getStagingSnapshot,
  subscribeStaging,
  uploadMediaBatch
} from "@/lib/media";
import { downloadLocalMedia, downloadMediaUrl } from "@/lib/localMediaDownload";
import { isTransient } from "@/lib/offline";
import { loadStageReferences } from "@/lib/referenceCache";
import { StageComputedCount } from "@/components/designworkshop/StageComputedCount";
import type { MediaFile, MediaType } from "@/lib/types";

function ToolLoading() {
  return <p className="min-h-10 text-xs leading-5 text-ink-500">Getting this tool ready…</p>;
}
const SignaturePad = deferredTool(() => import("@/components/SignaturePad").then((m) => m.SignaturePad), {
  placeholder: ToolLoading
});
const IdentityCardReader = deferredTool(
  () => import("@/components/designworkshop/IdentityCardReader").then((m) => m.IdentityCardReader),
  { placeholder: ToolLoading }
);
const PhotoMeasureField = deferredTool(
  () => import("@/components/designworkshop/PhotoMeasureField").then((m) => m.PhotoMeasureField),
  { placeholder: ToolLoading }
);
const SketchRectifyField = deferredTool(
  () => import("@/components/designworkshop/SketchRectifyField").then((m) => m.SketchRectifyField),
  { placeholder: ToolLoading }
);
const SketchTraceField = deferredTool(
  () => import("@/components/sketches/upload/SketchTraceField").then((m) => m.SketchTraceField),
  { placeholder: ToolLoading }
);
for (const tool of [SignaturePad, IdentityCardReader, PhotoMeasureField, SketchRectifyField, SketchTraceField]) {
  registerWarm(tool.preload);
}

type StagedTile = {
  name: string;
  url: string | null;
  sizeBytes: number;
  blob: Blob | null;
  mimeType: string;
  capturedAt: number;
};

export const DW_MEDIA_RECORD_TYPE = "designWorkshop";

export type StageMediaPlace = { entityKey: string; rowKey: string | null; fieldKey: string };

function pendingKeyOf(place: StageMediaPlace): string {
  return [place.entityKey, place.rowKey ?? "", place.fieldKey].join("\u0000");
}

type PendingMediaStore = {
  held: Record<string, File[]>;
  write: (key: string, update: PendingUpdate) => void;
};

type PendingUpdate = File[] | ((current: File[]) => File[]);

const StagePendingMediaContext = createContext<PendingMediaStore | null>(null);

const EMPTY_PENDING: File[] = [];

export function StagePendingMediaProvider({ children }: { children: React.ReactNode }) {
  const [held, setHeld] = useState<Record<string, File[]>>({});

  const write = useCallback((key: string, update: PendingUpdate) => {
    setHeld((current) => {
      const existing = current[key] ?? EMPTY_PENDING;
      const files = typeof update === "function" ? update(existing) : update;
      if (existing.length === files.length && existing.every((file, index) => file === files[index])) return current;
      if (files.length) return { ...current, [key]: files };
      const next = { ...current };
      delete next[key];
      return next;
    });
  }, []);

  const store = useMemo<PendingMediaStore>(() => ({ held, write }), [held, write]);

  return <StagePendingMediaContext.Provider value={store}>{children}</StagePendingMediaContext.Provider>;
}

function usePendingMedia(place: StageMediaPlace, slot = ""): [File[], (update: PendingUpdate) => void] {
  const store = useContext(StagePendingMediaContext);
  const key = slot ? `${pendingKeyOf(place)}\u0000${slot}` : pendingKeyOf(place);
  const [local, setLocal] = useState<File[]>(EMPTY_PENDING);
  const hoisted = store?.held[key] ?? EMPTY_PENDING;
  const write = useCallback(
    (update: PendingUpdate) => {
      if (store) store.write(key, update);
      else setLocal(update);
    },
    [store, key, setLocal]
  );
  return [store ? hoisted : local, write];
}

function stagingOwnerFor(place: StageMediaPlace): string {
  return `dw-field:${pendingKeyOf(place)}`;
}

export type StageCaptureContext = {
  location?: unknown;
  recordedAt?: string;
  recordedTimezone?: string;
  point?: DwGeoValue | null;
};

export type FieldInputProps = {
  field: DwField;
  value: DwValue | undefined;
  onChange: (value: DwValue) => void;
  entity: DwEntity;
  row: DwEntryData;
  onPatch: (values: Record<string, DwValue>) => void;
  workshopId: string;
  caption?: { field: DwField; value: DwValue | undefined; onChange: (value: DwValue) => void };

  disabled?: boolean;
  error?: string | null;
  place?: { stageKey?: string | null; entityKey?: string | null; rowKey?: string | null };
  capture?: StageCaptureContext;
  stamp?: DwFieldStamp | null;
  recordFormMountedOver?: string | null;
};

function FieldHint({
  field,
  error,
  hintId,
  errorId,
  stamp
}: {
  field: DwField;
  error?: string | null;
  hintId: string;
  errorId: string;
  stamp?: DwFieldStamp | null;
}) {
  const hint = [field.help, field.unit ? `Measured in ${field.unit}.` : ""].filter(Boolean).join(" ");
  return (
    <>
      {hint ? (
        <p id={hintId} className="text-xs leading-5 text-ink-500">
          {hint}
        </p>
      ) : null}
      <FieldProvenance stamp={stamp} />
      {error ? (
        <p id={errorId} role="alert" className="text-xs font-medium leading-5 text-error-600">
          {error}
        </p>
      ) : null}
    </>
  );
}

function labelText(field: DwField): string {
  return field.required ? `${field.label} *` : field.label;
}

function derivedPlaceholder(field: DwField, value: DwValue | undefined, row: DwEntryData): string | undefined {
  if (!isDerived(field)) return undefined;
  if (!(value === null || value === undefined || value === "")) return undefined;
  const computed = deriveValue(field, row);
  if (computed === null) return undefined;
  return `${computed}${field.unit ? ` ${field.unit}` : ""} (computed)`;
}

function declaredMaxItems(field: DwField): number | null {
  return typeof field.maxItems === "number" && field.maxItems > 0 ? field.maxItems : null;
}

function effectiveMaxItems(field: DwField): number {
  return declaredMaxItems(field) ?? DW_DEFAULT_MAX_ITEMS;
}

export const DW_CRAFT_NAME_FROM_LIST = "From the craft picked from the list.";

export function stageCraftNameLocked(entity: Pick<DwEntity, "fields">, field: Pick<DwField, "key">): boolean {
  return field.key === "craftName" && entity.fields.some((sibling) => sibling.key === "craftRef" && sibling.refModel === "Craft");
}

export function FieldInput({
  field,
  value,
  onChange,
  entity,
  row,
  onPatch,
  workshopId,
  caption,
  disabled,
  error,
  place,
  capture,
  stamp,
  recordFormMountedOver
}: FieldInputProps) {
  const controlId = useId();
  const labelId = `${controlId}-label`;
  const hintId = `${controlId}-hint`;
  const errorId = `${controlId}-error`;
  const formatErrorId = `${controlId}-format`;
  const detailsId = `${controlId}-details`;
  const [detailsOpen, setDetailsOpen] = useState(false);

  const formatProblem = fieldFormatError(field, value);
  const richTextUpload = useMemo(() => ({ recordType: DW_MEDIA_RECORD_TYPE, recordId: workshopId }), [workshopId]);
  const formatShownBelow = Boolean(formatProblem) && !formatShownByControl(entity, field);

  const hasHint = Boolean(field.help || field.unit);
  const describedBy =
    [hasHint ? hintId : null, error ? errorId : null, formatShownBelow ? formatErrorId : null]
      .filter(Boolean)
      .join(" ") || undefined;
  const invalid = error || formatProblem ? true : undefined;

  const [dictationRefusal, setDictationRefusal] = useState<{ dropped: number; over: string } | null>(null);
  const dictationDropped =
    dictationRefusal && dictationRefusal.over === inputValue(value) ? dictationRefusal.dropped : 0;

  const appendDictated = useCallback(
    (text: string) => {
      const existing = inputValue(value);
      const joiner = !existing || /\s$/.test(existing) ? "" : " ";
      const composed = `${existing}${joiner}${text}`;
      if (field.maxLength && composed.length > field.maxLength) {
        setDictationRefusal({ dropped: composed.length - field.maxLength, over: existing });
        return;
      }
      setDictationRefusal(null);
      onChange(composed);
    },
    [value, onChange, field.maxLength]
  );

  const dictationNotice =
    dictationDropped > 0 ? (
      <p className="text-xs font-medium leading-5 text-error-600">
        Dictation not added — {dictationDropped} character{dictationDropped === 1 ? "" : "s"} over the {field.maxLength}
        limit. Shorten the text, then dictate again.
      </p>
    ) : null;

  const formatNotice = formatShownBelow ? (
    <p id={formatErrorId} role="alert" className="text-xs font-medium leading-5 text-error-600">
      {formatProblem}
    </p>
  ) : null;

  const labelRow = (label: React.ReactNode) =>
    hasDetails(field.details) ? (
      <>
        <div className="flex min-w-0 items-center gap-1.5">
          {label}
          <InfoToggle
            fieldLabel={field.label}
            expanded={detailsOpen}
            panelId={detailsId}
            onToggle={() => setDetailsOpen((open) => !open)}
          />
        </div>
        {detailsOpen ? <InfoPanel id={detailsId}>{field.details}</InfoPanel> : null}
      </>
    ) : (
      label
    );

  const labelled = (control: React.ReactNode) => (
    <div className="grid min-w-0 gap-1">
      {labelRow(
        <label className="field-label" htmlFor={controlId}>
          {labelText(field)}
        </label>
      )}
      {control}
      <FieldHint field={field} error={error} hintId={hintId} errorId={errorId} stamp={stamp} />
      {formatNotice}
      {dictationNotice}
    </div>
  );

  const unlabelled = (control: React.ReactNode) => (
    <div className="grid min-w-0 gap-1">
      {labelRow(
        <span className="field-label" id={labelId}>
          {labelText(field)}
        </span>
      )}
      {control}
      <FieldHint field={field} error={error} hintId={hintId} errorId={errorId} stamp={stamp} />
      {formatNotice}
      {dictationNotice}
    </div>
  );

  if (field.computedKind) return unlabelled(<StageComputedCount field={field} value={value} labelId={labelId} />);

  switch (field.type) {
    case "PHONE":
      return unlabelled(<StagePhoneField value={value} onChange={onChange} disabled={disabled} />);

    case "TEXT":
    case "URL":
    case "EMAIL": {
      const address = field.type === "TEXT" ? addressListRole(entity, field) : null;
      if (address) {
        return unlabelled(
          <StageAddressField
            field={field}
            role={address}
            value={value}
            row={row}
            onChange={onChange}
            onPatch={onPatch}
            labelId={labelId}
            describedBy={describedBy}
            invalid={invalid}
            disabled={disabled}
          />
        );
      }
      if (workshopTitleRole(field)) {
        return unlabelled(
          <StageWorkshopField
            field={field}
            value={value}
            onChange={onChange}
            labelId={labelId}
            describedBy={describedBy}
            invalid={invalid}
            disabled={disabled}
            dictation={(control) => (
              <DictationButton
                onCommit={appendDictated}
                disabled={disabled}
                fieldLabel={field.label}
                workshopId={workshopId}
              >
                {control}
              </DictationButton>
            )}
          />
        );
      }
      if (ownWorkshopTitleRole(field)) {
        return unlabelled(
          <StageWorkshopNameField
            field={field}
            value={value}
            workshopKind={inputValue(row.workshopKind)}
            onChange={onChange}
            labelId={labelId}
            describedBy={describedBy}
            invalid={invalid}
            disabled={disabled}
            dictation={(control) => (
              <DictationButton
                onCommit={appendDictated}
                disabled={disabled}
                fieldLabel={field.label}
                workshopId={workshopId}
                beside
              >
                {control}
              </DictationButton>
            )}
          />
        );
      }
      const mediaNote = field.type === "TEXT" ? recordMediaNoteRole(entity, field) : null;
      if (mediaNote) {
        return unlabelled(
          <StageMediaNoteField
            field={field}
            role={mediaNote}
            row={row}
            value={value}
            onChange={onChange}
            labelId={labelId}
            describedBy={describedBy}
            invalid={invalid}
            disabled={disabled}
            dictation={(control) => (
              <DictationButton
                onCommit={appendDictated}
                disabled={disabled}
                fieldLabel={field.label}
                workshopId={workshopId}
              >
                {control}
              </DictationButton>
            )}
          />
        );
      }
      const identityTarget = field.type === "TEXT" ? identityNumberField(entity) : null;
      const craftLocked = field.type === "TEXT" && stageCraftNameLocked(entity, field);
      const textBox = (
        <input
          id={controlId}
          className="field-input"
          type={field.type === "URL" ? "url" : field.type === "EMAIL" ? "email" : "text"}
          maxLength={field.maxLength || undefined}
          aria-describedby={describedBy}
          aria-invalid={invalid}
          value={inputValue(value)}
          disabled={disabled}
          readOnly={craftLocked}
          onChange={(event) => {
            if (!craftLocked) onChange(event.target.value);
          }}
        />
      );
      if (craftLocked) {
        return labelled(
          <>
            {textBox}
            <p className="text-xs leading-5 text-ink-500">{DW_CRAFT_NAME_FROM_LIST}</p>
          </>
        );
      }
      return labelled(
        <>
          {field.type === "TEXT" ? (
            <DictationButton onCommit={appendDictated} disabled={disabled} fieldLabel={field.label} workshopId={workshopId}>
              {textBox}
            </DictationButton>
          ) : (
            textBox
          )}
          {identityTarget?.key === field.key ? (
            <IdentityCardCapture
              kind="PEHCHAN"
              targetLabel={field.label}
              currentValue={inputValue(value)}
              aadhaarProblem={aadhaarValidationError}
              onUse={(next) => onChange(next)}
              disabled={disabled}
            />
          ) : null}
          {field.storeMasked && inputValue(value).trim() && !isMaskedIdentityNumber(inputValue(value)) ? (
            <p className="text-xs leading-5 text-ink-500">
              Only the last four digits are kept: “XXXX XXXX {inputValue(value).replace(/\D/g, "").slice(-4) || "####"}”.
              Check it against the card before saving.
            </p>
          ) : field.storeMasked && isAadhaarMaskValue(inputValue(value)) ? (
            <p className="text-xs leading-5 text-ink-500">
              Masked: “{inputValue(value)}” is stored and printed. The full number is on the artisan record.
            </p>
          ) : null}
        </>
      );
    }

    case "LONG_TEXT":
      if (field.reportRole === "BULLETS") {
        return unlabelled(
          <NumberedListField
            value={inputValue(value)}
            onChange={(next) => onChange(next || null)}
            labelId={labelId}
            disabled={disabled}
            describedBy={describedBy}
            invalid={Boolean(error)}
            renderDictation={(commit) => (
              <DictationButton onCommit={commit} disabled={disabled} fieldLabel={field.label} workshopId={workshopId} />
            )}
          />
        );
      }
      return labelled(
        <DictationButton
          onCommit={appendDictated}
          disabled={disabled}
          fieldLabel={field.label}
          workshopId={workshopId}
          multiline
        >
          <textarea
            id={controlId}
            className="field-input min-h-24"
            maxLength={field.maxLength || undefined}
            aria-describedby={describedBy}
            aria-invalid={invalid}
            value={inputValue(value)}
            disabled={disabled}
            onChange={(event) => onChange(event.target.value)}
          />
        </DictationButton>
      );

    case "RICH_TEXT":
      return unlabelled(
        <MountWhenNear preview={toPlain(fromStored(value))} labelledBy={labelId} disabled={disabled}>
        <RichTextEditor
          value={value}
          onChange={(next) => onChange(next)}
          disabled={disabled}
          ariaLabelledBy={labelId}
          ariaLabel={field.label}
          workshopId={workshopId}
          maxLength={field.maxLength || undefined}
          listKind={field.reportRole === "BULLETS" ? "ORDERED_ITEM" : undefined}
          upload={richTextUpload}
        />
        </MountWhenNear>
      );

    case "INT":
    case "DECIMAL":
    case "PERCENT":
      return labelled(
        <input
          id={controlId}
          className="field-input"
          type="number"
          placeholder={derivedPlaceholder(field, value, row)}
          step={field.type === "INT" ? 1 : "any"}
          min={field.minValue ?? undefined}
          max={field.maxValue ?? (field.type === "PERCENT" ? 100 : undefined)}
          aria-describedby={describedBy}
          aria-invalid={invalid}
          value={inputValue(value)}
          disabled={disabled}
          onChange={(event) => onChange(event.target.value === "" ? null : event.target.value)}
        />
      );

    case "MONEY":
      return labelled(
        <div className="relative">
          <span aria-hidden className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-sm text-ink-500">
            ₹
          </span>
          <input
            id={controlId}
            className="field-input pl-7"
            type="text"
            inputMode="decimal"
            placeholder={derivedPlaceholder(field, value, row)}
            aria-describedby={describedBy}
            aria-invalid={invalid}
            value={inputValue(value)}
            disabled={disabled}
            onChange={(event) => onChange(event.target.value === "" ? null : event.target.value)}
          />
        </div>
      );

    case "DATE": {
      const range = dateRangePartner(entity, field);
      const partnerIso = range ? inputValue(row[range.partner.key]).slice(0, 10) : "";
      return labelled(
        <DateField
          id={controlId}
          value={inputValue(value).slice(0, 10)}
          min={range?.role === "end" ? partnerIso || undefined : undefined}
          max={range?.role === "start" ? partnerIso || undefined : undefined}
          minLabel={range?.role === "end" ? range.partner.label : undefined}
          maxLabel={range?.role === "start" ? range.partner.label : undefined}
          describedBy={describedBy}
          invalid={Boolean(error)}
          disabled={disabled}
          onChange={(iso) => onChange(iso || null)}
        />
      );
    }

    case "TIME":
      return labelled(
        <TimeField
          id={controlId}
          value={inputValue(value).slice(0, 5)}
          describedBy={describedBy}
          invalid={Boolean(error)}
          disabled={disabled}
          onChange={(hhmm) => onChange(hhmm || null)}
        />
      );

    case "BOOL":
      return unlabelled(
        <BoolField
          labelId={labelId}
          describedBy={describedBy}
          value={value}
          onChange={onChange}
          disabled={disabled}
          required={field.required}
        />
      );

    case "ENUM":
      return unlabelled(
        <Dropdown
          value={inputValue(value)}
          onChange={(next) => onChange(next || null)}
          options={field.options ?? []}
          placeholder="Select"
          disabled={disabled}
          ariaLabel={field.label}
          describedBy={describedBy}
        />
      );

    case "MULTI_ENUM":
      return unlabelled(
        <MultiEnumField
          field={field}
          entity={entity}
          row={row}
          workshopId={workshopId}
          describedBy={describedBy}
          value={value}
          onChange={onChange}
          disabled={disabled}
        />
      );

    case "TAGS":
      return unlabelled(
        <TagsField
          labelId={labelId}
          describedBy={describedBy}
          field={field}
          value={value}
          onChange={onChange}
          disabled={disabled}
        />
      );

    case "GEO":
      return unlabelled(
        <StageGeoField
          labelId={labelId}
          value={value}
          onChange={onChange}
          disabled={disabled}
          recordingPlace={capture?.point ?? null}
        />
      );

    case "REF": {
      if (!field.refModel) {
        return labelled(
          <>
            <input
              id={controlId}
              className="field-input"
              type="text"
              aria-describedby={[`${controlId}-manual`, describedBy].filter(Boolean).join(" ")}
              aria-invalid={invalid}
              value={inputValue(value)}
              disabled={disabled}
              onChange={(event) => onChange(event.target.value || null)}
            />
            <p id={`${controlId}-manual`} className="text-xs leading-5 text-ink-500">
              Type the reference by hand.
            </p>
          </>
        );
      }
      return unlabelled(
        <StageReferenceSelect
          workshopId={workshopId}
          entity={entity}
          field={field}
          row={row}
          value={value}
          onChange={onChange}
          onPatch={onPatch}
          disabled={disabled}
          labelId={labelId}
          recordFormMountedOver={recordFormMountedOver}
        />
      );
    }

    case "IMAGE":
    case "IMAGE_LIST":
    case "FILE":
    case "AUDIO":
    case "VIDEO": {
      const identityTarget = offersIdentityOcr(entity, field) ? identityNumberField(entity) : null;
      const signature = offersSignaturePad(field);
      const measureTargets = offersPhotoMeasure(entity, field) ? measurableLengthFields(entity) : [];
      const offersDerivedDrawing = offersSketchRectify(entity, field);
      const sketchSources: SketchSource[] = offersDerivedDrawing
        ? sketchSourceFields(entity).flatMap((source) =>
            listValue(row[source.key]).map((ref) => ({ ref, fieldLabel: source.label }))
          )
        : [];
      return unlabelled(
        <>
          <MediaField
            labelId={labelId}
            describedBy={describedBy}
            field={field}
            value={value}
            onChange={onChange}
            workshopId={workshopId}
            disabled={disabled}
            place={place}
            capture={capture}
            extra={({ files, originals, attach, detach, local }) => (
              <>
                {signature ? (
                  <SignaturePad
                    label={field.label}
                    disabled={disabled}
                    onCapture={attach}
                  />
                ) : null}
                {identityTarget ? (
                  <IdentityCardReader
                    files={files}
                    originals={originals}
                    targetLabel={identityTarget.label}
                    currentValue={inputValue(row[identityTarget.key])}
                    disabled={disabled}
                    onConfirm={(digits) => onPatch({ [identityTarget.key]: digits })}
                    onDiscard={detach}
                  />
                ) : null}
                {measureTargets.length ? (
                  <PhotoMeasureField
                    photos={[
                      ...files
                        .filter((media) => media.mediaType === "IMAGE" && media.url)
                        .map((media) => ({ key: media.id, name: media.originalFilename, url: media.url as string })),
                      ...local
                    ]}
                    targets={measureTargets}
                    row={row}
                    disabled={disabled}
                    onPropose={(key, proposed) => onPatch({ [key]: proposed })}
                  />
                ) : null}
                <MediaAiVerbs workshopId={workshopId} files={files} local={local} disabled={disabled} />
                {sketchSources.length ? (
                  <SketchRectifyField
                    sources={sketchSources}
                    targetLabel={field.label}
                    disabled={disabled}
                    onAttach={attach}
                  />
                ) : null}
                {offersDerivedDrawing ? (
                  <SketchTraceField targetLabel={field.label} disabled={disabled} onAttach={attach} />
                ) : null}
              </>
            )}
          />
          {caption ? (
            <div className="mt-2 rounded-lg bg-surface-50 px-3 py-2">
              <label className="field-label" htmlFor={`${controlId}-caption`}>
                {labelText(caption.field)}
              </label>
              <input
                id={`${controlId}-caption`}
                className="field-input mt-1"
                type="text"
                maxLength={caption.field.maxLength || undefined}
                value={inputValue(caption.value)}
                disabled={disabled}
                onChange={(event) => caption.onChange(event.target.value)}
              />
            </div>
          ) : null}
        </>
      );
    }

    default: {
      const noteId = `${controlId}-unsupported`;
      return labelled(
        <>
          <input
            id={controlId}
            className="field-input"
            type="text"
            disabled
            aria-describedby={[noteId, describedBy].filter(Boolean).join(" ")}
            value={inputValue(value)}
          />
          <p id={noteId} className="text-xs leading-5 text-ink-500">
            Can&apos;t show this question. Reload to update the app — its answer is kept.
          </p>
        </>
      );
    }
  }
}

export function fieldReadsRow(entity: DwEntity, field: DwField): boolean {
  switch (field.type) {
    case "PHONE":
    case "LONG_TEXT":
    case "RICH_TEXT":
    case "TIME":
    case "BOOL":
    case "ENUM":
    case "TAGS":
    case "GEO":
      return false;
    case "TEXT":
    case "URL":
    case "EMAIL":
      return (
        (field.type === "TEXT" && (addressListRole(entity, field) !== null || recordMediaNoteRole(entity, field) !== null)) ||
        Boolean(ownWorkshopTitleRole(field))
      );
    case "INT":
    case "DECIMAL":
    case "PERCENT":
    case "MONEY":
      return isDerived(field);
    case "DATE":
      return dateRangePartner(entity, field) !== null;
    case "IMAGE":
    case "IMAGE_LIST":
    case "FILE":
    case "AUDIO":
    case "VIDEO":
      return offersIdentityOcr(entity, field) || offersPhotoMeasure(entity, field) || offersSketchRectify(entity, field);
    default:
      return true;
  }
}

function sameFieldInputProps(previous: FieldInputProps, next: FieldInputProps): boolean {
  const keys = new Set([...Object.keys(previous), ...Object.keys(next)]) as Set<keyof FieldInputProps>;
  for (const key of keys) {
    if (Object.is(previous[key], next[key])) continue;
    if (key === "row" && next.entity === previous.entity && next.field === previous.field && !fieldReadsRow(next.entity, next.field)) {
      continue;
    }
    return false;
  }
  return true;
}

const MemoFieldInput = memo(FieldInput, sameFieldInputProps);

export function StageFieldInput(props: FieldInputProps) {
  const latest = useRef(props);
  useInsertionEffect(() => {
    latest.current = props;
  });
  const onChange = useCallback((value: DwValue) => latest.current.onChange(value), []);
  const onPatch = useCallback((values: Record<string, DwValue>) => latest.current.onPatch(values), []);
  const onCaptionChange = useCallback((value: DwValue) => latest.current.caption?.onChange(value), []);

  const captionField = props.caption?.field;
  const captionValue = props.caption?.value;
  const caption = useMemo(
    () => (captionField ? { field: captionField, value: captionValue, onChange: onCaptionChange } : undefined),
    [captionField, captionValue, onCaptionChange]
  );

  const hasPlace = props.place !== undefined;
  const stageKey = props.place?.stageKey;
  const entityKey = props.place?.entityKey;
  const rowKey = props.place?.rowKey;
  const place = useMemo(
    () => (hasPlace ? { stageKey, entityKey, rowKey } : undefined),
    [hasPlace, stageKey, entityKey, rowKey]
  );

  return <MemoFieldInput {...props} onChange={onChange} onPatch={onPatch} caption={caption} place={place} />;
}

function StagePhoneField({
  value,
  onChange,
  disabled
}: {
  value: DwValue | undefined;
  onChange: (next: DwValue) => void;
  disabled?: boolean;
}) {
  const incoming = inputValue(value);
  const emitted = useRef(incoming);
  const [seed, setSeed] = useState(incoming);
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    if (incoming === emitted.current) return;
    emitted.current = incoming;
    setSeed(incoming);
    setGeneration((current) => current + 1);
  }, [incoming]);

  return (
    <PhoneField
      key={generation}
      defaultValue={seed}
      disabled={disabled}
      mirror={false}
      onValueChange={(next) => {
        emitted.current = next;
        onChange(next || null);
      }}
    />
  );
}

function BoolField({
  labelId,
  describedBy,
  value,
  onChange,
  disabled,
  required
}: {
  labelId: string;
  describedBy?: string;
  value: DwValue | undefined;
  onChange: (value: DwValue) => void;
  disabled?: boolean;
  required: boolean;
}) {
  const current = typeof value === "boolean" ? value : null;
  const choice = (label: string, choiceValue: boolean) => {
    const active = current === choiceValue;
    return (
      <button
        type="button"
        aria-pressed={active}
        disabled={disabled}
        onClick={() => onChange(choiceValue)}
        className={
          active
            ? "inline-flex min-h-10 items-center justify-center rounded-md bg-purple-700 px-4 py-2 text-sm font-medium text-white transition"
            : "inline-flex min-h-10 items-center justify-center rounded-md border border-line-200 bg-card px-4 py-2 text-sm font-medium text-ink-900 transition hover:border-purple-300 hover:bg-purple-50"
        }
      >
        {label}
      </button>
    );
  };

  return (
    <div className="flex flex-wrap items-center gap-2" role="group" aria-labelledby={labelId} aria-describedby={describedBy}>
      {choice("Yes", true)}
      {choice("No", false)}
      {current !== null && !required ? (
        <button type="button" className="text-xs font-medium text-ink-500 underline" disabled={disabled} onClick={() => onChange(null)}>
          Clear
        </button>
      ) : null}
      {current === null ? <span className="text-xs text-ink-500">Not answered</span> : null}
    </div>
  );
}

function TagsField({
  labelId,
  describedBy,
  field,
  value,
  onChange,
  disabled
}: {
  labelId: string;
  describedBy?: string;
  field: DwField;
  value: DwValue | undefined;
  onChange: (value: DwValue) => void;
  disabled?: boolean;
}) {
  const tags = listValue(value);
  const [draft, setDraft] = useState("");
  const [refused, setRefused] = useState<string[]>([]);
  const declaredCap = declaredMaxItems(field);
  const cap = effectiveMaxItems(field);
  const capId = `${useId()}-cap`;

  const commit = useCallback(
    (raw: string) => {
      const parts = raw
        .split(",")
        .map((part) => part.trim())
        .filter(Boolean);
      if (!parts.length) return;
      const seen = new Set(tags.map((tag) => tag.toLowerCase()));
      const next = [...tags];
      const turnedAway: string[] = [];
      for (const part of parts) {
        if (seen.has(part.toLowerCase())) continue;
        if (next.length >= cap) {
          turnedAway.push(part);
          continue;
        }
        seen.add(part.toLowerCase());
        next.push(part);
      }
      setRefused(turnedAway);
      if (next.length !== tags.length) onChange(next);
      setDraft("");
    },
    [tags, onChange, cap]
  );

  const refusalNotice = !refused.length
    ? null
    : `${
        declaredCap === null
          ? `${field.label} is full`
          : `${field.label} holds at most ${declaredCap} ${declaredCap === 1 ? "entry" : "entries"}`
      }. Not added: ${refused.join(", ")}. Remove one, then type ${refused.length === 1 ? "it" : "them"} again.`;

  return (
    <div className="grid gap-2">
      {tags.length ? (
        <ul className="flex flex-wrap gap-1.5">
          {tags.map((tag) => (
            <li key={tag}>
              <span className="inline-flex items-center gap-1 rounded-full bg-field-200 px-2.5 py-1 text-xs font-medium text-ink-900">
                {tag}
                <button
                  type="button"
                  aria-label={`Remove ${tag}`}
                  disabled={disabled}
                  onClick={() => onChange(tags.filter((item) => item !== tag))}
                  className="text-ink-500 transition hover:text-ink-900"
                >
                  <X className="h-3 w-3" aria-hidden />
                </button>
              </span>
            </li>
          ))}
        </ul>
      ) : null}
      <input
        className="field-input"
        type="text"
        aria-labelledby={labelId}
        aria-describedby={[describedBy, declaredCap !== null ? capId : null].filter(Boolean).join(" ") || undefined}
        placeholder="Type a value and press Enter"
        value={draft}
        disabled={disabled}
        onChange={(event) => setDraft(event.target.value)}
        onBlur={() => commit(draft)}
        onKeyDown={(event) => {
          if (event.key !== "Enter" && event.key !== ",") return;
          event.preventDefault();
          event.stopPropagation();
          commit(draft);
        }}
      />
      {declaredCap !== null ? (
        <p id={capId} className="text-xs leading-5 text-ink-500">
          {declaredCap - tags.length <= 0
            ? `Full at ${declaredCap}. Remove one to add another.`
            : `${declaredCap - tags.length} of ${declaredCap} left.`}
        </p>
      ) : null}
      <p role="status" aria-live="polite" className={refusalNotice ? "text-xs leading-5 text-amber-800 dark:text-amber-100" : "sr-only"}>
        {refusalNotice}
      </p>
    </div>
  );
}

export function capListGrowth(
  held: string[],
  next: string[],
  cap: number
): { kept: string[]; refused: string[] } {
  if (next.length <= cap || next.length <= held.length) return { kept: next, refused: [] };
  const keep = new Set(next.filter((token) => held.includes(token)));
  for (const token of next) {
    if (keep.size >= cap) break;
    keep.add(token);
  }
  return {
    kept: next.filter((token) => keep.has(token)),
    refused: next.filter((token) => !keep.has(token))
  };
}

function orphanRow(id: string): DropdownOption {
  return {
    value: id,
    label: `Linked record ${id.slice(0, 8)}`,
    hint: "already on this field, not in the current list"
  };
}

const OPAQUE_ID = /^[a-z0-9]{16,}$/;

export function heldRow(token: string): DropdownOption {
  if (OPAQUE_ID.test(token.trim())) return orphanRow(token);
  return { value: token, label: token, hint: "typed on this field, not a linked record" };
}

export function referenceMultiOptions({
  payload,
  values,
  seen
}: {
  payload: DwReferencePayload | null;
  values: string[];
  seen?: ReadonlyMap<string, DwReferenceOption>;
}): DropdownOption[] {
  const rows: DropdownOption[] = (payload?.options ?? []).map((option) => ({
    value: option.id,
    label: option.label,
    hint: option.sublabel || undefined
  }));
  const drawn = new Set(rows.map((option) => option.value));
  for (const id of values) {
    if (!id || drawn.has(id)) continue;
    drawn.add(id);
    const remembered = seen?.get(id);
    rows.push(
      remembered
        ? { value: id, label: remembered.label, hint: remembered.sublabel || undefined }
        :
          heldRow(id)
    );
  }
  return rows;
}

export function referenceMultiNotice({
  field,
  parentLabel,
  awaitingCascade,
  payload,
  problem,
  loading,
  query,
  cachedAt
}: {
  field: DwField;
  parentLabel: string;
  awaitingCascade: boolean;
  payload: DwReferencePayload | null;
  problem: string | null;
  loading: boolean;
  query: string;
  cachedAt?: string | null;
}): string {
  if (awaitingCascade) {
    return `Choose ${parentLabel || "the field above"} first — this list depends on it.`;
  }
  if (problem) {
    return `${problem} What's chosen is still listed and can be removed.`;
  }
  if (!payload) return loading ? "Loading the records this field can link to…" : "";
  const lines = scopeNoticeLines(field, payload, cachedAt);
  if (!payload.options.length) {
    const term = query.trim();
    const scopeExplainsEmpty =
      field.refScope === "WORKSHOP" && payload.scopedToWorkshop && !payload.filtered;
    if (term) {
      if (scopeExplainsEmpty) lines.length = 0;
      lines.push(
        loading
          ? "Searching…"
          : scopeExplainsEmpty
            ? `Nothing matches “${term}” in this design workshop's linked workshop.`
            : `Nothing matches “${term}”.`
      );
    } else if (payload.filtered) {
      lines.push(
        `Nothing recorded under the ${parentLabel || "record chosen above"} yet.`
      );
    } else if (!scopeExplainsEmpty) {
      lines.push(
        `Nothing to link for ${field.label} yet. Create the record elsewhere first, then link it here.`
      );
    }
  }
  return lines.join(" ");
}

const REF_SEARCH_DEBOUNCE_MS = 300;

function useReferenceSearch({
  workshopId,
  field,
  filterValue,
  query,
  active
}: {
  workshopId: string;
  field: DwField;
  filterValue: string;
  query: string;
  active: boolean;
}): { payload: DwReferencePayload | null; loading: boolean; problem: string | null; cachedAt: string | null } {
  const [state, setState] = useState<{
    payload: DwReferencePayload | null;
    loading: boolean;
    problem: string | null;
    cachedAt: string | null;
  }>({ payload: null, loading: false, problem: null, cachedAt: null });
  const generation = useRef(0);

  useEffect(() => {
    if (!active || !field.refModel) {
      generation.current += 1;
      setState({ payload: null, loading: false, problem: null, cachedAt: null });
      return;
    }
    const current = generation.current + 1;
    generation.current = current;
    setState((previous) => ({ ...previous, loading: true, problem: null }));
    const model = field.refModel as string;
    const filterBy = field.refFilterBy ? filterValue || null : null;
    const search = query.trim() || null;
    const request = () => listStageReferences(workshopId, { model, scope: field.refScope, filterBy, search });
    const timer = window.setTimeout(
      () => {
        if (search || filterBy) {
          request()
            .then((payload) => {
              if (generation.current !== current) return;
              setState({ payload, loading: false, problem: null, cachedAt: null });
            })
            .catch((error) => {
              if (generation.current !== current) return;
              setState({
                payload: null,
                loading: false,
                problem: error instanceof Error ? error.message : "The list could not be loaded.",
                cachedAt: null
              });
            });
          return;
        }
        void loadStageReferences<DwReferencePayload>({
          workshopId,
          model,
          scope: field.refScope,
          isEmpty: (payload) => payload.options.length === 0,
          fetch: request,
          onPayload: (payload, cachedAt) => {
            if (generation.current !== current) return;
            setState({ payload, loading: cachedAt !== null, problem: null, cachedAt });
          }
        }).then((outcome) => {
          if (generation.current !== current) return;
          if (outcome.source === "none") {
            setState({
              payload: null,
              loading: false,
              problem: outcome.error instanceof Error ? outcome.error.message : "The list could not be loaded.",
              cachedAt: null
            });
            return;
          }
          setState((previous) => ({ ...previous, loading: false }));
        });
      },
      query ? REF_SEARCH_DEBOUNCE_MS : 0
    );
    return () => window.clearTimeout(timer);
  }, [workshopId, field.refModel, field.refScope, field.refFilterBy, filterValue, query, active]);

  return state;
}

function ReferenceMultiSelect({
  workshopId,
  entity,
  field,
  row,
  values,
  onCommit,
  disabled,
  describedBy
}: {
  workshopId: string;
  entity: DwEntity;
  field: DwField;
  row: DwEntryData;
  values: string[];
  onCommit: (next: string[], labelOf: (token: string) => string) => void;
  disabled?: boolean;
  describedBy?: string;
}) {
  const [query, setQuery] = useState("");
  const [seen, setSeen] = useState<Map<string, DwReferenceOption>>(() => new Map());
  const noticeId = useId();

  const filterValue = field.refFilterBy ? inputValue(row[field.refFilterBy]) : "";
  const awaitingCascade = Boolean(field.refFilterBy) && !filterValue;
  const parentLabel = field.refFilterBy
    ? entity.fields.find((candidate) => candidate.key === field.refFilterBy)?.label ?? ""
    : "";

  const { payload, loading, problem, cachedAt } = useReferenceSearch({
    workshopId,
    field,
    filterValue,
    query,
    active: !awaitingCascade
  });

  useEffect(() => {
    const fetched = payload?.options;
    if (!fetched?.length) return;
    setSeen((previous) => {
      const next = new Map(previous);
      for (const option of fetched) next.set(option.id, option);
      return next;
    });
  }, [payload]);

  const options = referenceMultiOptions({ payload, values, seen });
  const notice = referenceMultiNotice({
    field,
    parentLabel,
    awaitingCascade,
    payload,
    problem,
    loading,
    query,
    cachedAt
  });
  const labelOf = (token: string) => options.find((option) => option.value === token)?.label ?? token;

  return (
    <div className="grid gap-2">
      {awaitingCascade || disabled ? null : (
        <div onInput={(event) => event.stopPropagation()}>
          <SearchInput value={query} onChange={setQuery} placeholder={`Search ${field.label.toLowerCase()}`} />
        </div>
      )}
      {notice ? (
        <p id={noticeId} className="text-xs leading-5 text-ink-500">
          {notice}
        </p>
      ) : null}
      <MultiSelectDropdown
        values={values}
        onChange={(next) => onCommit(next, labelOf)}
        options={options}
        placeholder="Select"
        emptyLabel={notice || "No records to choose from yet."}
        disabled={disabled}
        ariaLabel={field.label}
        describedBy={[describedBy, notice ? noticeId : null].filter(Boolean).join(" ") || undefined}
        searchable={false}
        capHint={
          awaitingCascade
            ? `Answer ${parentLabel || "the field above"} first.`
            : "Search above to find the rest."
        }
        confirmLabel="Confirm"
      />
    </div>
  );
}

function MultiEnumField({
  field,
  entity,
  row,
  workshopId,
  describedBy,
  value,
  onChange,
  disabled
}: {
  field: DwField;
  entity: DwEntity;
  row: DwEntryData;
  workshopId: string;
  describedBy?: string;
  value: DwValue | undefined;
  onChange: (value: DwValue) => void;
  disabled?: boolean;
}) {
  const values = listValue(value);
  const [refused, setRefused] = useState<string[]>([]);
  const declaredCap = declaredMaxItems(field);
  const cap = effectiveMaxItems(field);
  const capId = `${useId()}-cap`;
  const describes = [describedBy, declaredCap !== null ? capId : null].filter(Boolean).join(" ") || undefined;

  const commit = (next: string[], labelOf: (token: string) => string) => {
    const outcome = capListGrowth(values, next, cap);
    setRefused(outcome.refused.map(labelOf));
    onChange(outcome.kept);
  };

  const optionLabel = (option: string) =>
    field.options?.find((candidate) => candidate.value === option)?.label ?? option;

  const refusalNotice = !refused.length
    ? null
    : `${
        declaredCap === null
          ? `${field.label} is full`
          : `${field.label} holds at most ${declaredCap} ${declaredCap === 1 ? "entry" : "entries"}`
      }. Not added: ${refused.join(", ")}. Remove one, then pick ${refused.length === 1 ? "it" : "them"} again.`;

  return (
    <div className="grid gap-2">
      {field.refModel ? (
        <ReferenceMultiSelect
          workshopId={workshopId}
          entity={entity}
          field={field}
          row={row}
          values={values}
          onCommit={commit}
          disabled={disabled}
          describedBy={describes}
        />
      ) : (
        <MultiSelectDropdown
          values={values}
          onChange={(next) => commit(next, optionLabel)}
          options={field.options ?? []}
          placeholder="Select"
          emptyLabel="No options in this list"
          disabled={disabled}
          ariaLabel={field.label}
          describedBy={describes}
          confirmLabel="Confirm"
        />
      )}
      {declaredCap !== null ? (
        <p id={capId} className="text-xs leading-5 text-ink-500">
          {declaredCap - values.length <= 0
            ? `Full at ${declaredCap}. Remove one to add another.`
            : `${declaredCap - values.length} of ${declaredCap} left.`}
        </p>
      ) : null}
      <p role="status" aria-live="polite" className={refusalNotice ? "text-xs leading-5 text-amber-800 dark:text-amber-100" : "sr-only"}>
        {refusalNotice}
      </p>
    </div>
  );
}

const ALLOWED_TYPES: Partial<Record<DwField["type"], MediaType[]>> = {
  IMAGE: ["IMAGE"],
  IMAGE_LIST: ["IMAGE"],
  AUDIO: ["AUDIO"],
  VIDEO: ["VIDEO"]
};

type RefusedFile = { key: string; name: string };

function refusedKeyOf(file: File): string {
  return `${file.name}:${file.size}:${file.lastModified}`;
}

function forgetRefused(current: RefusedFile[], attached: File[]): RefusedFile[] {
  if (!current.length || !attached.length) return current;
  const done = new Set(attached.map(refusedKeyOf));
  return current.filter((entry) => !done.has(entry.key));
}

type GateRefusal = { key: string; name: string; faults: GateFault[] };

function MediaField({
  labelId,
  describedBy,
  field,
  value,
  onChange,
  workshopId,
  disabled,
  place,
  capture,
  extra
}: {
  labelId: string;
  describedBy?: string;
  field: DwField;
  value: DwValue | undefined;
  onChange: (value: DwValue) => void;
  workshopId: string;
  disabled?: boolean;
  place?: { stageKey?: string | null; entityKey?: string | null; rowKey?: string | null };
  capture?: StageCaptureContext;
  extra?: (context: {
    files: MediaFile[];
    originals: Record<string, File>;
    attach: (file: File) => void;
    detach: (id: string) => void;
    local: { key: string; name: string; url: string }[];
  }) => React.ReactNode;
}) {
  const multiple = field.type === "IMAGE_LIST";
  const ids = useMemo(() => listValue(value), [value]);
  const mediaGroupId = useId();
  const capId = `${mediaGroupId}-cap`;
  const floorId = `${mediaGroupId}-floor`;
  const declaredCap = multiple ? declaredMaxItems(field) : null;
  const cap = multiple ? effectiveMaxItems(field) : null;
  const floor = multiple ? declaredMinItems(field) : null;
  const [files, setFiles] = useState<Record<string, MediaFile | null>>({});
  const [staged, setStaged] = useState<Record<string, StagedTile | null>>(
    {}
  );
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [refused, setRefused] = useState<RefusedFile[]>([]);
  const [gateRefused, setGateRefused] = useState<GateRefusal[]>([]);
  const mediaPlace = useMemo<StageMediaPlace>(
    () => ({ entityKey: place?.entityKey ?? "", rowKey: place?.rowKey ?? null, fieldKey: field.key }),
    [place?.entityKey, place?.rowKey, field.key]
  );
  const [pending, setPending] = usePendingMedia(mediaPlace);
  const [screening, setScreening] = usePendingMedia(mediaPlace, "screening");
  const claimedRef = useRef(new Set<File>());
  const admittedFindingsRef = useRef(new Map<string, GateFault[]>());
  const [originals, setOriginals] = useState<Record<string, File>>({});
  const staging = useSyncExternalStore(subscribeStaging, getStagingSnapshot, getServerStagingSnapshot);

  useEffect(() => {
    let cancelled = false;
    const unknown = ids.filter((id) => !isLocalMediaRef(id) && !files[id]);
    if (!unknown.length) return;
    (async () => {
      const resolved = await Promise.all(
        unknown.map(async (id) => {
          try {
            return [id, await fetchMediaRow(id)] as const;
          } catch {
            return [id, null] as const;
          }
        })
      );
      if (cancelled) return;
      setFiles((current) => {
        const next = { ...current };
        for (const [id, file] of resolved) next[id] = file;
        return next;
      });
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ids.join(",")]);

  useEffect(() => {
    let cancelled = false;
    const created: string[] = [];
    const localRefs = ids.filter(isLocalMediaRef);
    if (!localRefs.length) {
      setStaged({});
      return;
    }
    (async () => {
      const tiles: Record<string, StagedTile | null> = {};
      const healed = new Map<string, string>();
      for (const ref of localRefs) {
        const media = await readLocalMedia(ref);
        if (!media) {
          tiles[ref] = null;
          continue;
        }
        if (media.remoteMediaId) {
          healed.set(ref, media.remoteMediaId);
          continue;
        }
        const url = media.blob && media.mimeType.startsWith("image/") ? URL.createObjectURL(media.blob) : null;
        if (url) created.push(url);
        tiles[ref] = {
          name: media.name,
          url,
          sizeBytes: media.sizeBytes,
          blob: media.blob,
          mimeType: media.mimeType,
          capturedAt: media.capturedAt
        };
      }
      if (cancelled) {
        created.forEach((url) => URL.revokeObjectURL(url));
        return;
      }
      setStaged(tiles);
      if (healed.size) onChange(multiple ? ids.map((id) => healed.get(id) ?? id) : (healed.get(ids[0]) ?? ids[0]));
    })();
    return () => {
      cancelled = true;
      created.forEach((url) => URL.revokeObjectURL(url));
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ids.join(",")]);

  async function stageOffline(chosen: File[]): Promise<void> {
    const refs: string[] = [];
    for (const file of chosen) {
      const { ref } = await stageLocalMedia(workshopId, file, {
        stageKey: place?.stageKey ?? null,
        entityKey: place?.entityKey ?? null,
        fieldKey: field.key,
        clientKey: place?.rowKey ?? null,
        caption: field.label
      });
      refs.push(ref);
    }
    onChange(multiple ? [...ids, ...refs] : refs[0]);
    setNotice(
      `${refs.length} file${refs.length === 1 ? "" : "s"} saved on this device. ${refs.length === 1 ? "It uploads" : "They upload"} when you're online.`
    );
  }

  const settle = useCallback(
    async (chosen: File[]) => {
      setProblem(null);
      setNotice(null);
      try {
        if (typeof navigator !== "undefined" && navigator.onLine === false) {
          await stageOffline(chosen);
          return;
        }
        const { uploaded, failed, uploadedByIndex } = await uploadMediaBatch({
          files: chosen,
          linkedRecordType: DW_MEDIA_RECORD_TYPE,
          linkedRecordId: workshopId,
          caption: field.label,
          location: capture?.location,
          recordedAt: capture?.recordedAt,
          recordedTimezone: capture?.recordedTimezone
        });
        if (uploaded.length) {
          setFiles((current) => {
            const next = { ...current };
            for (const media of uploaded) next[media.id] = media;
            return next;
          });
          setOriginals((current) => {
            const next = { ...current };
            uploadedByIndex.forEach((media, index) => {
              const source = chosen[index];
              if (media && source) next[media.id] = source;
            });
            return next;
          });
          const raised: CapturedFinding[] = [];
          const raisedAt = new Date().toISOString();
          uploadedByIndex.forEach((media, index) => {
            const source = chosen[index];
            if (!media || !source) return;
            const key = refusedKeyOf(source);
            const faults = admittedFindingsRef.current.get(key);
            if (!faults?.length) return;
            for (const fault of faults) {
              raised.push({
                mediaId: media.id,
                fileName: source.name,
                flag: fault.flag,
                severity: fault.severity,
                note: fault.message,
                raisedAt
              });
            }
            admittedFindingsRef.current.delete(key);
          });
          if (raised.length) recordCaptureFindings(workshopId, raised);
          const uploadedIds = uploaded.map((media) => media.id);
          onChange(multiple ? [...listValue(value), ...uploadedIds] : uploadedIds[uploadedIds.length - 1]);
        }
        if (failed.length) {
          setProblem(
            `Didn't upload: ${failed.map((item) => item.name).join(", ")}. Attach again.`
          );
        }
      } catch (err) {
        if (isTransient(err)) {
          try {
            await stageOffline(chosen);
            return;
          } catch (stageError) {
            setProblem(
              stageError instanceof Error
                ? `Upload failed and the file couldn't be kept: ${stageError.message}`
                : "Upload failed and the file couldn't be kept."
            );
            return;
          }
        }
        setProblem(err instanceof Error ? err.message : "The upload failed.");
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [workshopId, field.label, capture, multiple, onChange, value]
  );

  useEffect(() => {
    if (!pending.length) return;
    const offline = typeof navigator !== "undefined" && navigator.onLine === false;
    const finished = pending.filter((file) => {
      if (claimedRef.current.has(file)) return false;
      if (offline) return true;
      const entry = staging.get(file);
      if (!entry) return file.size === 0;
      return entry.status === "ready";
    });
    if (!finished.length) return;
    finished.forEach((file) => claimedRef.current.add(file));
    const settling = settle(finished);
    setPending((current) => current.filter((file) => !finished.includes(file)));
    void settling;
  }, [pending, staging, settle, setPending]);

  function detach(id: string) {
    const remaining = ids.filter((item) => item !== id);
    onChange(multiple ? remaining : (remaining[0] ?? null));
    if (isLocalMediaRef(id)) void removeLocalMedia(id.slice(LOCAL_MEDIA_PREFIX.length));
  }

  function attachExisting(rows: MediaFile[]) {
    const held = new Set(listValue(value));
    const fresh = rows.filter((row) => !held.has(row.id));
    if (!fresh.length) return;
    setFiles((current) => {
      const next = { ...current };
      for (const row of fresh) next[row.id] = row;
      return next;
    });
    const chosen = fresh.map((row) => row.id);
    onChange(multiple ? [...listValue(value), ...chosen] : chosen[chosen.length - 1]);
  }

  const room = cap === null ? null : Math.max(0, cap - ids.length - pending.length - screening.length);

  const accounted = ids.length + pending.length + screening.length;

  function acceptFiles(next: File[]) {
    if (room === null || next.length <= pending.length) {
      setPending(next);
      return;
    }
    const added = next.slice(pending.length);
    if (added.length <= room) {
      admit(next.slice(0, pending.length), added);
      setRefused((current) => forgetRefused(current, added));
      return;
    }
    const kept = added.slice(0, room);
    const dropped = added.slice(room);
    admit(next.slice(0, pending.length), kept);
    setRefused((current) => {
      const carried = forgetRefused(current, kept);
      const known = new Set(carried.map((entry) => entry.key));
      const grown = [...carried];
      for (const file of dropped) {
        const key = refusedKeyOf(file);
        if (known.has(key)) continue;
        known.add(key);
        grown.push({ key, name: file.name });
      }
      return grown;
    });
  }

  function admit(prefix: File[], added: File[]) {
    setPending(prefix);
    const held = new Set([...prefix, ...screening].map(refusedKeyOf));
    const fresh = added.filter((file) => !held.has(refusedKeyOf(file)));
    const photographs = fresh.filter(isMeasurableImage);
    const rest = fresh.filter((file) => !isMeasurableImage(file));
    if (rest.length) setPending((current) => [...current, ...rest]);
    if (!photographs.length) return;
    setScreening((current) => [...current, ...photographs]);
    void screen(photographs);
  }

  async function screen(photographs: File[]) {
    const attached = ids
      .map((id) => files[id])
      .filter((file): file is MediaFile => Boolean(file))
      .map((file) => ({ label: file.originalFilename, checksum: file.checksum ?? null }));
    for (const file of photographs) {
      const measurement = await measureImageFile(file);
      if (!measurement) {
        release(file, null);
        continue;
      }
      const checksum = await computeChecksum(file);
      const verdict = gatePhotograph({ measurement, checksum, attached });
      if (verdict.admitted && checksum) attached.push({ label: file.name, checksum });
      release(file, verdict);
    }
  }

  function release(file: File, verdict: ReturnType<typeof gatePhotograph> | null) {
    const key = refusedKeyOf(file);
    setScreening((current) => current.filter((entry) => entry !== file));
    if (!verdict || verdict.admitted) {
      if (verdict?.faults.length) admittedFindingsRef.current.set(key, verdict.faults);
      setPending((current) => [...current, file]);
      setGateRefused((current) => (current.some((entry) => entry.key === key) ? current.filter((entry) => entry.key !== key) : current));
      return;
    }
    setGateRefused((current) => {
      const without = current.filter((entry) => entry.key !== key);
      return [...without, { key, name: file.name, faults: verdict.faults }];
    });
  }

  const refusalNotice = (() => {
    if (!refused.length) return null;
    const printableCeiling = multiple ? declaredMaxItems(field) : null;
    return (
      `${capCeilingClause({ label: field.label, declaredCap: printableCeiling, accounted })}. ` +
      `Not attached: ${refused.map((entry) => entry.name).join(", ")}. ` +
      `Remove something already attached, then pick ${refused.length === 1 ? "it" : "them"} again — ` +
      `this list stays until you do.`
    );
  })();

  const gateNotice = gateRefusalSentence(gateRefused);

  const screeningNotice = screening.length
    ? `Checking ${screening.length} photograph${screening.length === 1 ? "" : "s"} on this device ` +
      `before ${screening.length === 1 ? "it uploads" : "they upload"}…`
    : null;

  const carousel = useMemo(() => {
    if (declaredCap === null) {
      return { items: [] as CarouselItem[], unreadable: 0, notImages: 0, pending: 0 };
    }
    const items: CarouselItem[] = [];
    let unreadable = 0;
    let notImages = 0;
    let pending = 0;
    for (const id of ids) {
      if (isLocalMediaRef(id)) {
        const local = staged[id];
        if (local === undefined) {
          pending += 1;
          continue;
        }
        if (local === null) {
          unreadable += 1;
          continue;
        }
        if (!local.url) {
          notImages += 1;
          continue;
        }
        items.push({ key: id, id: null, name: local.name, mediaType: "IMAGE", url: local.url });
        continue;
      }
      const file = files[id];
      if (file === undefined) {
        pending += 1;
        continue;
      }
      if (file === null) {
        unreadable += 1;
        continue;
      }
      if (file.mediaType !== "IMAGE") {
        notImages += 1;
        continue;
      }
      items.push({
        key: id,
        id: file.id,
        name: file.originalFilename,
        mediaType: file.mediaType,
        mimeType: file.mimeType,
        sizeBytes: file.sizeBytes,
        url: file.url,
        caption: file.caption
      });
    }
    return { items, unreadable, notImages, pending };
  }, [declaredCap, ids, staged, files]);

  const carouselOmissionNotice = (() => {
    const missing = carousel.unreadable + carousel.notImages;
    if (!carousel.items.length || missing === 0) return null;
    const parts: string[] = [];
    if (carousel.unreadable) parts.push(`${carousel.unreadable} cannot be read from here`);
    if (carousel.notImages) {
      parts.push(carousel.notImages === 1 ? "1 is not an image" : `${carousel.notImages} are not images`);
    }
    if (carousel.pending) {
      parts.push(
        carousel.pending === 1 ? "1 is still being looked up" : `${carousel.pending} are still being looked up`
      );
    }
    const named = missing + carousel.pending;
    const clauses =
      parts.length > 2 ? `${parts.slice(0, -1).join(", ")}, and ${parts[parts.length - 1]}` : parts.join(", and ");
    return (
      `Showing ${carousel.items.length} of the ${ids.length} file${ids.length === 1 ? "" : "s"} attached to ` +
      `${field.label.toLowerCase()}: ${clauses}. The list above has a line for ` +
      `${named === 1 ? "it" : "each of them"}.`
    );
  })();

  return (
    <div
      className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3"
      role="group"
      aria-labelledby={labelId}
      aria-describedby={
        [describedBy, declaredCap !== null ? capId : null, floor !== null ? floorId : null]
          .filter(Boolean)
          .join(" ") || undefined
      }
    >
      {ids.length ? (
        <ul className="grid gap-2">
          {ids.map((id) => {
            const local = isLocalMediaRef(id) ? staged[id] : undefined;
            const file = local ? undefined : files[id];
            const attachmentIcon = (
              <span className="grid h-10 w-10 shrink-0 place-items-center rounded-sm bg-field-200 text-field-600 dark:text-purple-300">
                <Paperclip className="h-4 w-4" aria-hidden />
              </span>
            );
            return (
              <li key={id} className="rounded-md border border-line-200 bg-card px-3 py-2">
                <div className="flex items-center gap-2">
                {local?.url ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img
                    src={local.url}
                    alt={local.name}
                    className="h-10 w-10 shrink-0 rounded-sm object-cover"
                    loading="lazy"
                    decoding="async"
                  />
                ) : !local && file?.mediaType === "IMAGE" && file.url ? (
                  <RefreshingImage
                    media={file}
                    alt={file.caption || file.originalFilename || ""}
                    className="h-10 w-10 shrink-0 rounded-sm object-cover"
                    loading="lazy"
                    decoding="async"
                    fallback={
                      <span
                        title={`${file.originalFilename} couldn't be loaded.`}
                        className="grid h-10 w-10 shrink-0 place-items-center rounded-sm bg-field-200 text-ink-500"
                      >
                        <ImageOff className="h-4 w-4" aria-hidden />
                        <span className="sr-only">{`${file.originalFilename} couldn't be loaded.`}</span>
                      </span>
                    }
                  />
                ) : (
                  attachmentIcon
                )}
                <span className="min-w-0 flex-1 truncate text-sm text-ink-900">
                  {local
                    ? local.name
                    : file
                      ? file.originalFilename
                      : isLocalMediaRef(id)
                        ?
                          local === null
                          ? "File no longer on this device."
                          : "Reading this file off this device…"
                        : file === null
                          ? "File can't be read."
                          : "Looking this file up…"}
                </span>
                {local ? (
                  <span className="shrink-0 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
                    On this device only
                  </span>
                ) : null}
                {file && !file.url ? (
                  <span className="shrink-0 rounded-full bg-field-200 px-2 py-0.5 text-xs font-medium text-ink-700">
                    You don&apos;t have access to this file
                  </span>
                ) : null}
                {local ? (
                  <DownloadMediaButton
                    name={local.name}
                    onDownload={() => {
                      if (local.blob)
                        downloadLocalMedia(local.blob, { name: local.name, mimeType: local.mimeType, capturedAt: local.capturedAt });
                    }}
                    unavailableReason={local.blob ? null : "This copy is no longer on this device."}
                  />
                ) : local === null ? (
                  <DownloadMediaButton
                    name={id}
                    onDownload={() => undefined}
                    unavailableReason="This copy is no longer on this device."
                  />
                ) : file?.url ? (
                  <DownloadMediaButton
                    name={file.originalFilename}
                    onDownload={() => void downloadMediaUrl(file.url!, file.originalFilename, file.mimeType)}
                  />
                ) : null}
                <button
                  type="button"
                  className="text-xs font-medium text-ink-500 underline"
                  disabled={disabled}
                  onClick={() => detach(id)}
                >
                  Remove
                </button>
                </div>
                {(file?.mediaType === "AUDIO" || file?.mediaType === "VIDEO") && file.url ? <TranscriptViewer mediaId={file.id} audioUrl={file.url} video={file.mediaType === "VIDEO"} initiallyOpen={false} /> : null}
              </li>
            );
          })}
        </ul>
      ) : (
        <p className="text-sm text-ink-500">Nothing attached yet.</p>
      )}

      {carousel.items.length ? (
        <>
          <MediaCarousel items={carousel.items} noun={field.label.toLowerCase()} className="h-64 sm:h-80" />
          {carouselOmissionNotice ? (
            <p className="text-xs leading-5 text-ink-500">{carouselOmissionNotice}</p>
          ) : null}
        </>
      ) : null}

      {field.type === "FILE" && ids.length === 1 && !isLocalMediaRef(ids[0]) ? (
        <DocumentPreview mediaId={ids[0]} noun={field.label.toLowerCase()} className="h-112" />
      ) : null}

      {extra
        ? extra({
            files: ids.map((id) => files[id]).filter((file): file is MediaFile => Boolean(file)),
            originals,
            attach: (file) => setPending((current) => [...current, file]),
            detach,
            local: ids
              .filter(isLocalMediaRef)
              .map((ref) => {
                const tile = staged[ref];
                return tile && tile.url ? { key: ref, name: tile.name, url: tile.url } : null;
              })
              .filter((entry): entry is { key: string; name: string; url: string } => entry !== null)
          })
        : null}

      {declaredCap !== null ? (
        <p id={capId} className="text-xs leading-5 text-ink-500">
          {room === 0
            ? `${field.label} is full at ${declaredCap} file${declaredCap === 1 ? "" : "s"}. Remove one to attach another.`
            : `${room} of ${declaredCap} left.`}
        </p>
      ) : null}

      {floor !== null ? (
        <>
          <p id={floorId} className="text-xs leading-5 text-ink-500">
            {galleryFloorSentence({ floor, label: field.label })} {gateScopeSentence()}
          </p>
          <GalleryProgress
            floor={floor}
            label={field.label}
            labelledBy={labelId}
            counts={{
              held: ids.length,
              onDevice: ids.filter(isLocalMediaRef).length,
              uploading: pending.length,
              screening: screening.length
            }}
          />
        </>
      ) : null}

      <MediaCaptureField
        files={pending}
        onFilesChange={acceptFiles}
        stagingOwnerId={stagingOwnerFor(mediaPlace)}
        title={multiple ? `Add to ${field.label.toLowerCase()}` : field.label}
        description={
          multiple
            ? "Files upload straight away."
            : "Replaces the current file."
        }
        allowedTypes={ALLOWED_TYPES[field.type]}
        allowDocuments={field.type === "FILE"}
      />

      <MediaRepositoryPicker
        label={field.label}
        typeFilter={mediaPickerTypeFilter(ALLOWED_TYPES[field.type])}
        multiple={multiple}
        attachedIds={ids}
        room={room}
        declaredCap={declaredCap}
        accounted={accounted}
        disabled={disabled}
        onAttach={attachExisting}
      />

      <p
        role="status"
        aria-live="polite"
        className={screeningNotice ? "text-xs leading-5 text-ink-500" : "sr-only"}
      >
        {screeningNotice}
      </p>
      <p
        role="status"
        aria-live="polite"
        className={gateNotice ? "text-xs leading-5 text-amber-800 dark:text-amber-100" : "sr-only"}
      >
        {gateNotice}
      </p>
      <p
        role="status"
        aria-live="polite"
        className={refusalNotice ? "text-xs leading-5 text-amber-800 dark:text-amber-100" : "sr-only"}
      >
        {refusalNotice}
      </p>
      <p role="status" aria-live="polite" className={notice ? "text-xs leading-5 text-amber-800 dark:text-amber-100" : "sr-only"}>
        {notice}
      </p>
      <p role="alert" aria-live="assertive" className={problem ? "text-xs font-medium text-error-600" : "sr-only"}>
        {problem}
      </p>
    </div>
  );
}
