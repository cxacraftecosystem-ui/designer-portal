"use client";

import { CalendarField } from "@/components/ui/CalendarField";
import { useEffect, useId, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { useAuth } from "@/components/AuthProvider";
import { statusLabel } from "@/components/StatusBadge";
import { CarryForwardCards } from "@/components/CarryForwardCards";
import { Field, Select, TextInput } from "@/components/FormControls";
import { AadhaarField, aadhaarValidationError, isMaskedIdentityNumber } from "@/components/forms/AadhaarField";
import { IdentityCardCapture } from "@/components/forms/IdentityCardCapture";
import { CarryContextBanner, carryScope, useCarryContext, type CarryScopeState } from "@/components/forms/CarryContextBanner";
import { HeldPostNotice, useRecordFilingHold } from "@/components/designworkshop/HeldPostNotice";
import { DosDontsField } from "@/components/forms/DosDontsField";
import { DuplicateArtisanDialog } from "@/components/forms/DuplicateArtisanDialog";
import { PossibleDuplicateDialog, PossibleDuplicates } from "@/components/duplicates/PossibleDuplicates";
import type { InlineHostSeed, InlineRecordSurfaceProps, UseExistingArtisan } from "@/components/forms/inlineRecordHost";
import { LocationFields, type LocationInitialValues } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { PhoneField } from "@/components/forms/PhoneField";
import { GENDER_OPTIONS } from "@/lib/standardValues";
import { CraftAddDraftBoundary } from "@/components/forms/CraftAddDraftBoundary";
import { CraftSelect } from "@/components/forms/CraftSelect";
import { useWorkshopPicker, WorkshopPicker } from "@/components/forms/WorkshopPicker";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { UploadProgress } from "@/components/media/UploadProgress";
import { ArtisanCardPanel } from "@/components/artisans/ArtisanCardPanel";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { RichTextField } from "@/components/richtext/RichTextField";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { ApiError, apiErrorMessage, apiFetch } from "@/lib/api";
import { CRAFT_NOT_IN_LIST, useCraftCatalogue } from "@/lib/craftCatalogue";
import { locationFromForm, recordedAtFromForm, recordedTimezoneFromForm, textValue, useFocusField, useUnsavedChanges } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { collectExifMetadata, exifMetadataToRemark, inferMediaType, uploadMediaBatch, type BatchProgress } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { artisanBody, PEHCHAN_NO } from "@/lib/recordPayloads";
import {
  acknowledgedIds,
  checkDuplicates,
  checkKey,
  checkReady,
  possibleDuplicateHold,
  visibleCandidates,
  type DuplicateCandidate,
  type DuplicateCheckRequest,
  type PossibleDuplicateHold
} from "@/lib/duplicates";
import { canManageCrafts, hasRank } from "@/lib/permissions";
import {
  ARTISAN_FORM_CONTRACT,
  EXPERIENCE_MONTH_OPTIONS,
  EXPERIENCE_NOT_STATED,
  experienceMonthsLabel,
  missingArtisanDatesSentence
} from "@/lib/artisanFormContract";
import { EMAIL_PATTERN, emailValidationError } from "@/lib/textFormats";
import type { AadhaarLookupResult, Artisan, ArtisanIdentityConflict, ArtisanIdentityMatch, RecordStatus } from "@/lib/types";
import { RequiredMark } from "@/components/ui/RequiredMark";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { ArtisanConsentToggle } from "@/components/forms/ArtisanConsentToggle";
import { CrossPortalFind, CrossPortalLinkPanel } from "@/components/forms/CrossPortalLookup";
import {
  keepTypedPehchan,
  linkToFieldRepository,
  prefillFromMatch,
  prefillFromRefreshedArtisan,
  type ArtisanPrefill,
  type CrossPortalMatch
} from "@/lib/crossPortal";
import type { StatedPrefill } from "@/components/forms/statedPrefill";
import { LiveCompleteness } from "@/components/records/LiveCompleteness";

const genderOptions = GENDER_OPTIONS;

const PEHCHAN_YES = "true";

const PEHCHAN_NUMBER_REQUIRED =
  "Enter the Pehchan number, or set the card to 'No'.";

function errorDetail(error: unknown): unknown {
  if (!(error instanceof ApiError)) return null;
  const payload = error.payload;
  if (!payload || typeof payload !== "object" || !("detail" in payload)) return null;
  return (payload as { detail: unknown }).detail;
}

function readableError(error: unknown, fallback: string): string {
  return apiErrorMessage(error, fallback);
}

function identityConflict(error: unknown): ArtisanIdentityConflict | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null;
  const detail = errorDetail(error);
  if (!detail || typeof detail !== "object") return null;
  const conflict = detail as ArtisanIdentityConflict;
  return conflict.code === "artisan_identity_conflict" ? conflict : null;
}

async function findArtisanByAadhaar(digits: string | null, excludeArtisanId: string | null): Promise<ArtisanIdentityMatch | null> {
  const number = (digits ?? "").trim();
  if (!number || isMaskedIdentityNumber(number) || aadhaarValidationError(number)) return null;
  try {
    const result = await apiFetch<AadhaarLookupResult>("/artisans/lookup/aadhaar", {
      method: "POST",
      body: JSON.stringify({ number })
    });
    const found = result.found ? (result.artisan ?? null) : null;
    return found && found.id !== excludeArtisanId ? found : null;
  } catch {
    return null;
  }
}

function duplicateCheckRequest(form: FormData): DuplicateCheckRequest {
  const aadhaar = textValue(form, "aadhaarNumber");
  return {
    name: textValue(form, "name") ?? "",
    localName: textValue(form, "localName"),
    gender: textValue(form, "gender"),
    dateOfBirth: textValue(form, "dateOfBirth"),
    phone: textValue(form, "phone"),
    place: textValue(form, "place"),
    state: textValue(form, "state"),
    district: textValue(form, "district"),
    village: textValue(form, "village"),
    craftId: textValue(form, "craftId"),
    craftName: null,
    aadhaar: aadhaar && !isMaskedIdentityNumber(aadhaar) && !aadhaarValidationError(aadhaar) ? aadhaar : null,
    pehchanCardNumber: textValue(form, "pehchanCardNumber")
  };
}

function browserOffline(): boolean {
  return typeof navigator !== "undefined" && navigator.onLine === false;
}

function PehchanFields({
  initialAvailable,
  initialNumber,
  onDirty
}: {
  initialAvailable: boolean | null;
  initialNumber?: string | null;
  onDirty: () => void;
}) {
  const baseId = useId();
  const numberId = `${baseId}-pehchan-number`;
  const hintId = `${baseId}-pehchan-hint`;
  const [answer, setAnswer] = useState<boolean | null>(initialAvailable);
  const available = answer === true;
  const storedMask = isMaskedIdentityNumber(initialNumber) ? String(initialNumber).trim() : null;
  const [replacing, setReplacing] = useState(false);
  const [number, setNumber] = useState(storedMask ? "" : (initialNumber ?? ""));
  const keptMask = replacing || !available ? null : storedMask;

  return (
    <>
      <div className="grid content-start gap-1">
        <span className="field-label">
          Artisan Pehchan Card available
          <RequiredMark when />
        </span>
        <Select
          name="pehchanCardAvailable"
          value={answer === null ? "" : answer ? PEHCHAN_YES : PEHCHAN_NO}
          aria-label="Artisan Pehchan Card available"
          required
          onChange={(event) => {
            const next = event.target.value === PEHCHAN_YES;
            setAnswer(event.target.value === "" ? null : next);
            if (!next) setNumber("");
            onDirty();
          }}
        >
          <option value="">Select…</option>
          <option value={PEHCHAN_YES}>Yes</option>
          <option value={PEHCHAN_NO}>No</option>
        </Select>
      </div>
      <div className="grid content-start gap-1">
        <label className="field-label" htmlFor={numberId}>
          Artisan Pehchan Card number<RequiredMark when={available} />
        </label>
        <input
          id={numberId}
          name="pehchanCardNumber"
          className="field-input read-only:bg-surface-50 read-only:text-ink-500 disabled:cursor-not-allowed disabled:bg-surface-50 disabled:text-ink-500"
          type="text"
          autoComplete="off"
          placeholder={available ? "As printed on the card" : answer === null ? "Answer the card question first" : "No card on record"}
          value={keptMask ?? number}
          readOnly={Boolean(keptMask)}
          required={available}
          disabled={!available}
          aria-disabled={!available}
          aria-describedby={hintId}
          onInvalid={(event) => event.currentTarget.setCustomValidity(PEHCHAN_NUMBER_REQUIRED)}
          onChange={(event) => {
            event.currentTarget.setCustomValidity("");
            setNumber(event.currentTarget.value.toUpperCase());
          }}
        />
        <p id={hintId} className="text-xs text-ink-muted">
          {answer === null
            ? "Has the artisan a Pehchan card?"
            : !available
            ? 'No Pehchan card. Switch "available" to Yes to enter one.'
            : keptMask
              ? "On file but hidden. Saving leaves it unchanged."
              : "The PM Vishwakarma ID on the card."}
          {keptMask ? (
            <>
              {" "}
              <button
                type="button"
                className="font-semibold text-purple-700 dark:text-purple-300 underline"
                onClick={() => setReplacing(true)}
              >
                Replace this number
              </button>
            </>
          ) : null}
          {available && storedMask && replacing ? (
            <>
              {" "}
              <button
                type="button"
                className="font-semibold text-purple-700 dark:text-purple-300 underline"
                onClick={() => {
                  setReplacing(false);
                  setNumber("");
                }}
              >
                Keep the stored number
              </button>
            </>
          ) : null}
        </p>
        {available ? (
          <IdentityCardCapture
            kind="PEHCHAN"
            targetLabel="the Pehchan card number"
            currentValue={keptMask ?? number}
            aadhaarProblem={aadhaarValidationError}
            onUse={(value) => {
              setReplacing(true);
              setNumber(value);
              onDirty();
            }}
          />
        ) : null}
      </div>
    </>
  );
}

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
        title="A reviewer sets the final status."
      >
        {text}
      </span>
    </div>
  );
}

const ARTISAN_COMPLETENESS_DERIVE = (data: FormData) => ({
  locationId: data.get("latitude") || data.get("state") || null
});

export function ArtisanForm({
  initial,
  seed,
  footerFields,
  onCreated,
  onCancel,
  onDiscardAndLeave,
  onQueued,
  onUseExisting,
  fromCard
}: {
  initial?: Artisan;
  fromCard?: string | null;
  seed?: InlineHostSeed;
  onCancel?: () => void;
  onQueued?: () => void;
  onUseExisting?: UseExistingArtisan;
  onCreated?: (record: Artisan) => void;
} & InlineRecordSurfaceProps) {
  const router = useRouter();
  const { user } = useAuth();
  const canSetStatus = hasRank(user, "PROFESSOR");
  const identityLabelId = `${useId()}-identity`;
  const derivedFieldsId = useId();
  const dobId = `${derivedFieldsId}-dob`;
  const craftStartId = `${derivedFieldsId}-craft-start`;
  const formRef = useRef<HTMLFormElement>(null);
  const craftCatalogue = useCraftCatalogue();
  const [craftId, setCraftId] = useState(initial?.craftId ?? "");
  const craftListState: CarryScopeState = craftCatalogue.catalogue
    ? "loaded"
    : craftCatalogue.loading
      ? "pending"
      : "unavailable";
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  useFocusField(true);
  const [error, setError] = useState<string | null>(null);
  const [conflict, setConflict] = useState<ArtisanIdentityConflict | null>(null);
  const [duplicatePromptOpen, setDuplicatePromptOpen] = useState(false);
  const [checkingDuplicate, setCheckingDuplicate] = useState(false);
  const [duplicateCandidates, setDuplicateCandidates] = useState<DuplicateCandidate[]>([]);
  const [duplicateDismissed, setDuplicateDismissed] = useState<Set<string>>(() => new Set());
  const [duplicateHold, setDuplicateHold] = useState<PossibleDuplicateHold | null>(null);
  const lastCheckKey = useRef<string | null>(null);
  const checkTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const checkGeneration = useRef(0);
  const [saving, setSaving] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  const [savedRecord, setSavedRecord] = useState<Artisan | null>(null);
  const [email, setEmail] = useState(initial?.email ?? "");
  const [name, setName] = useState(initial?.name ?? "");
  const [localName, setLocalName] = useState(initial?.localName ?? "");
  const [place, setPlace] = useState(initial?.place ?? "");
  const [dateOfBirth, setDateOfBirth] = useState(
    initial?.dateOfBirth ? String(initial.dateOfBirth).slice(0, 10) : ""
  );
  const [craftStartDate, setCraftStartDate] = useState(
    initial?.craftStartDate ? String(initial.craftStartDate).slice(0, 10) : ""
  );
  const [formKey, setFormKey] = useState(0);
  const [crossMatch, setCrossMatch] = useState<CrossPortalMatch | null>(null);
  const [prefill, setPrefill] = useState<ArtisanPrefill>({});
  const [prefillKey, setPrefillKey] = useState(0);
  const [statedPrefill, setStatedPrefill] = useState<StatedPrefill | null>(null);
  const { dirty: typedSinceMount, markDirty, resetDirty } = useUnsavedChanges();
  const [backPromptOpen, setBackPromptOpen] = useState(false);
  const [promptFromCancel, setPromptFromCancel] = useState(false);
  const dirty = typedSinceMount;
  useLeaveGuard(dirty, () => setBackPromptOpen(true));
  const initialLocation = initial
    ? ((initial as Artisan & { location?: LocationInitialValues | null }).location ?? null)
    : undefined;
  const initialExperienceMonths = (initial as Artisan & { experienceMonths?: number | null } | undefined)
    ?.experienceMonths ?? null;
  const aadhaarRequired = !initial || Boolean(initial.aadhaarNumber?.trim());
  const workshop = useWorkshopPicker({
    initialWorkshopId: initial?.workshopId ?? seed?.workshopId,
    initialDesignWorkshopId: initial?.designWorkshopId,
    isEdit: Boolean(initial),
    resetKey: initial?.id ?? null
  });
  const filing = useRecordFilingHold(initial?.designWorkshopId, workshop.designWorkshopId, "artisan");
  const filingNoticeId = useId();

  const emailError = emailValidationError(email);
  const emailErrorId = `${identityLabelId}-email-error`;

  const todayIso = new Date().toISOString().slice(0, 10);

  const craftOptions = useMemo(() => craftCatalogue.catalogue?.crafts ?? [], [craftCatalogue.catalogue]);
  const craftFallbackLabels = useMemo(
    () => (initial?.craftId && initial.craft?.name ? { [initial.craftId]: initial.craft.name } : undefined),
    [initial?.craftId, initial?.craft?.name]
  );

  const carry = useCarryContext({
    enabled: !initial,
    scopes: [carryScope("craft", craftListState, craftOptions)],
    applies: seed?.workshopId ? ["craft"] : ["craft", "workshop"],
    onApply: (context) => {
      if (context.craftId) setCraftId(context.craftId);
      if (context.workshopId && !workshop.touched) workshop.setWorkshopId(context.workshopId);
    }
  });
  function clearCarriedContext() {
    carry.change();
    setCraftId("");
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

  function fillFromFieldRepository(match: CrossPortalMatch) {
    const values = keepTypedPehchan(prefillFromMatch(match), typedIdentityNumbers().pehchanCardNumber);
    if (values.name) setName(values.name);
    if (values.localName) setLocalName(values.localName);
    if (values.place) setPlace(values.place);
    if (values.dateOfBirth) setDateOfBirth(values.dateOfBirth);
    if (values.craftStartDate) setCraftStartDate(values.craftStartDate);
    if (values.craftId) setCraftId(values.craftId);
    setPrefill(values);
    setPrefillKey((key) => key + 1);
    if (match.statedAddress) {
      setStatedPrefill((current) => ({
        key: (current?.key ?? 0) + 1,
        state: match.statedAddress?.state,
        district: match.statedAddress?.district,
        village: match.statedAddress?.village,
        source: "Field Repository"
      }));
    }
    setCrossMatch(match);
    markDirty();
  }

  function takeRefreshedArtisan(artisan: Artisan | null, changed: string[]) {
    if (!artisan) return;
    const values = prefillFromRefreshedArtisan(artisan, changed);
    if (values.name) setName(values.name);
    if (values.localName) setLocalName(values.localName);
    if (values.dateOfBirth) setDateOfBirth(values.dateOfBirth);
    setPrefill((current) => ({ ...current, ...values }));
    setPrefillKey((key) => key + 1);
  }

  function typedIdentityNumbers() {
    const form = formRef.current;
    const read = (key: string) => {
      const value = form ? new FormData(form).get(key) : null;
      return typeof value === "string" && value.trim() && !isMaskedIdentityNumber(value) ? value.trim() : null;
    };
    return { aadhaarNumber: read("aadhaarNumber"), pehchanCardNumber: read("pehchanCardNumber") };
  }

  function discardEntry() {
    setDuplicatePromptOpen(false);
    setCrossMatch(null);
    setPrefill({});
    setStatedPrefill(null);
    setConflict(null);
    setError(null);
    setMediaFiles([]);
    setEmail(initial?.email ?? "");
    setDateOfBirth(initial?.dateOfBirth ? String(initial.dateOfBirth).slice(0, 10) : "");
    setCraftStartDate(initial?.craftStartDate ? String(initial.craftStartDate).slice(0, 10) : "");
    setName(initial?.name ?? "");
    setLocalName(initial?.localName ?? "");
    setPlace(initial?.place ?? "");
    resetDirty();
    setFormKey((key) => key + 1);
    if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
  }

  async function runDuplicateCheck(request: DuplicateCheckRequest): Promise<DuplicateCandidate[]> {
    if (initial || !checkReady(request) || browserOffline()) return [];
    const key = checkKey(request);
    if (key === lastCheckKey.current) return duplicateCandidates;
    lastCheckKey.current = key;
    const generation = ++checkGeneration.current;
    try {
      const found = await checkDuplicates(request);
      if (generation === checkGeneration.current) setDuplicateCandidates(found);
      return found;
    } catch {
      lastCheckKey.current = null;
      return [];
    }
  }

  function scheduleDuplicateCheck() {
    if (initial) return;
    if (checkTimer.current) clearTimeout(checkTimer.current);
    checkTimer.current = setTimeout(() => {
      const node = formRef.current;
      if (node) void runDuplicateCheck(duplicateCheckRequest(new FormData(node)));
    }, 600);
  }

  useEffect(
    () => () => {
      if (checkTimer.current) clearTimeout(checkTimer.current);
    },
    []
  );

  function openCandidate(candidate: DuplicateCandidate) {
    if (onUseExisting) {
      resetDirty();
      onUseExisting({ id: candidate.artisan.id, name: candidate.artisan.name, place: candidate.artisan.place ?? "" });
      return;
    }
    window.open(`/artisans/${candidate.artisan.id}`, "_blank", "noopener");
  }

  function setAside(ids: string[]) {
    setDuplicateDismissed((current) => new Set([...current, ...ids]));
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    if (filing.saveHeld) return;
    setError(null);
    setConflict(null);
    setCheckingDuplicate(true);
    const existing = await findArtisanByAadhaar(textValue(form, "aadhaarNumber"), initial?.id ?? null);
    setCheckingDuplicate(false);
    if (existing) {
      setConflict({
        code: "artisan_identity_conflict",
        field: "aadhaarNumber",
        message: `${existing.name} is already recorded with this Aadhaar number.`,
        existingArtisan: existing
      });
      setDuplicatePromptOpen(true);
      return;
    }
    if (!initial) await runDuplicateCheck(duplicateCheckRequest(form));
    if (!(await workshop.confirmSubmission())) return;
    setSaving(true);
    try {
      const exifItems = await collectExifMetadata(mediaFiles);
      const exifRemark = exifMetadataToRemark(exifItems);
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      const location = locationFromForm(form);
      const craftId = textValue(form, "craftId");
      if (!craftId) {
        setError(CRAFT_NOT_IN_LIST);
        setSaving(false);
        return;
      }
      const missingDates = missingArtisanDatesSentence({
        dateOfBirth: textValue(form, "dateOfBirth"),
        craftStartDate: textValue(form, "craftStartDate")
      });
      if (missingDates) {
        setError(missingDates);
        setSaving(false);
        return;
      }
      const payload = artisanBody(form, {
        craftId,
        workshopId: workshop.workshopId || null,
        designWorkshopId: workshop.designWorkshopId || null,
        initialStatus: initial?.status,
        formContract: ARTISAN_FORM_CONTRACT,
        ...(initial ? {} : { duplicateAcknowledged: acknowledgedIds(duplicateDismissed) }),
        exif: { items: exifItems, remark: exifRemark }
      });
      const outcome = await saveOrQueue<Artisan>({
        label: `Artisan · ${payload.name || "Untitled"}`,
        endpoint: initial ? `/artisans/${initial.id}` : "/artisans",
        method: initial ? "PATCH" : "POST",
        body: payload,
        media: [
          {
            files: mediaFiles,
            linkedRecordType: "artisan",
            caption: `Field media for ${payload.name || "artisan"}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined
          }
        ]
      });
      if (outcome.queued) {
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
      if (!initial && crossMatch) {
        try {
          await linkToFieldRepository(saved.id, crossMatch.remoteArtisanId, fromCard || null);
        } catch {
        }
      }
      if (mediaFiles.length) {
        const { failed } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "artisan",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.name}`,
          location,
          recordedAt,
          recordedTimezone,
          extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined,
          onProgress: setUploadProgress
        });
        setUploadProgress(null);
        if (failed.length) {
          setError(
            `Saved, but these didn't upload: ${failed.map((f) => f.name).join(", ")}. Reopen to retry.`
          );
          setSaving(false);
          if (onCreated) onCreated(saved);
          return;
        }
      }
      resetDirty();
      if (onCreated) {
        onCreated(saved);
      } else if (initial) {
        router.push("/artisans");
        router.refresh();
      } else {
        setSavedRecord(saved);
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
      }
    } catch (err) {
      const hold = possibleDuplicateHold(err);
      const duplicate = hold ? null : identityConflict(err);
      if (hold) {
        setDuplicateHold(hold);
      } else if (duplicate) {
        setConflict(duplicate);
        setDuplicatePromptOpen(true);
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
      } else {
        setError(readableError(err, "Unable to save artisan"));
      }
    } finally {
      setSaving(false);
      setUploadProgress(null);
    }
  }

  if (savedRecord) {
    return (
      <div className="grid gap-6">
        <div className="panel p-4">
          <p className="text-sm font-medium text-ink">
            Saved &ldquo;{savedRecord.name}&rdquo;.
          </p>
          <ActionGroup className="mt-3">
            <button
              type="button"
              className="field-button-secondary"
              onClick={() => {
                setSavedRecord(null);
                setMediaFiles([]);
                setEmail("");
                setCrossMatch(null);
                setPrefill({});
                setStatedPrefill(null);
                setDateOfBirth("");
                setCraftStartDate("");
                setName("");
                setLocalName("");
                setPlace("");
              }}
            >
              Add another artisan
            </button>
            <button type="button" className="field-button-secondary" onClick={() => { router.push("/artisans"); router.refresh(); }}>
              Back to artisans
            </button>
          </ActionGroup>
        </div>
        <ArtisanCardPanel artisanId={savedRecord.id} />
        <CarryForwardCards
          context={{
            artisanId: savedRecord.id,
            artisanName: savedRecord.name,
            place: savedRecord.place,
            craftId: savedRecord.craftId,
            craftName: savedRecord.craft?.name,
            workshopId: savedRecord.workshopId,
            workshopName: workshop.workshops.find((w) => w.id === savedRecord.workshopId)?.title ?? null
          }}
        />
      </div>
    );
  }

  const conflictArtisan = conflict?.existingArtisan ?? null;

  return (
    <>
      <form
        key={formKey}
        ref={formRef}
        onSubmit={submit}
        onInput={markDirty}
        onBlur={scheduleDuplicateCheck}
        onKeyDown={handleFormEnter}
        className="panel grid gap-4 p-4"
      >
        {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
        <CarryContextBanner offer={carry.applied} onChange={clearCarriedContext} />
        <LiveCompleteness
          kind="artisan"
          formRef={formRef}
          stored={initial ?? null}
          derive={ARTISAN_COMPLETENESS_DERIVE}
          counts={{ photos: mediaFiles.filter((file) => inferMediaType(file) === "IMAGE").length }}
        />
        {initial ? null : (
          <PossibleDuplicates
            candidates={visibleCandidates(duplicateCandidates, duplicateDismissed)}
            onOpen={openCandidate}
            onDifferent={() => setAside(visibleCandidates(duplicateCandidates, duplicateDismissed).map((c) => c.artisan.id))}
          />
        )}
        {conflict ? (
          <div role="alert" className="rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm text-amber-800">
            <p className="font-medium">{conflict.message}</p>
            {conflictArtisan ? (
              onUseExisting ? (
                <button
                  type="button"
                  className="mt-1 inline-block font-medium underline"
                  onClick={() => {
                    resetDirty();
                    onUseExisting(conflictArtisan);
                  }}
                >
                  Use {conflictArtisan.name}
                  {conflictArtisan.place ? ` (${conflictArtisan.place})` : ""}
                </button>
              ) : (
                <Link className="mt-1 inline-block font-medium underline" href={`/artisans/${conflictArtisan.id}/edit`}>
                  Open {conflictArtisan.name}
                  {conflictArtisan.place ? ` (${conflictArtisan.place})` : ""}
                </Link>
              )
            ) : null}
            <p className="mt-1 text-xs">
              Nothing was saved. Correct the number, or {onUseExisting ? "use" : "edit"} the existing record instead.
            </p>
          </div>
        ) : null}
        <DictationUnavailableNotice />
        <div className="grid gap-3 md:grid-cols-2">
          <WorkshopPicker state={workshop} onDirty={markDirty} saving={saving} disabled={filing.recordHeld} />
          <DictatedTextInput
            name="name"
            label="Name"
            required
            titleCased
            explainWhenUnavailable={false}
            value={name}
            onChange={(next) => {
              setName(next);
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
          <FieldBlock label="Craft" required fieldKey="craft">
            <CraftAddDraftBoundary
              formRef={formRef}
              onLeave={resetDirty}
              onCraftAdded={(addedId, craft) => {
                setCraftId(addedId);
                markDirty();
                if (craft) carry.remember({ craftId: craft.id, craftName: craft.name }, { explicit: true });
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
                onChange={(nextId, craft) => {
                  setCraftId(nextId);
                  if (craft) carry.remember({ craftId: craft.id, craftName: craft.name }, { explicit: true });
                }}
              />
            </CraftAddDraftBoundary>
          </FieldBlock>
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
          <Field label="Gender">
            <Select
              key={`gender-${prefillKey}`}
              name="gender"
              defaultValue={prefill.gender ?? (initial?.gender?.trim() ? initial.gender : "")}
              onChange={markDirty}
            >
              <option value="">Select gender</option>
              {genderOptions.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
          <div className="grid min-w-0 gap-1" data-field="dateOfBirth">
            <CalendarField
              id={dobId}
              label="Date of birth"
              name="dateOfBirth"
              value={dateOfBirth}
              onChange={(iso) => {
                setDateOfBirth(iso);
                markDirty();
              }}
              max={todayIso}
              required
            />
          </div>
          <div className="grid min-w-0 gap-1" data-field="craftStartDate">
            <CalendarField
              id={craftStartId}
              label="Craft start date"
              name="craftStartDate"
              value={craftStartDate}
              onChange={(iso) => {
                setCraftStartDate(iso);
                markDirty();
              }}
              max={todayIso}
              required
            />
          </div>
          <Field label="Experience (years)">
            <TextInput
              key={`experience-years-${prefillKey}`}
              name="experienceYears"
              type="number"
              inputMode="numeric"
              min={0}
              max={90}
              step={1}
              defaultValue={prefill.experienceYears ?? initial?.experienceYears ?? ""}
              onChange={markDirty}
            />
          </Field>
          <FieldBlock label="Experience (months)">
            <Select
              key={`experience-months-${prefillKey}`}
              name="experienceMonths"
              defaultValue={
                prefill.experienceMonths != null
                  ? String(prefill.experienceMonths)
                  : initialExperienceMonths != null
                    ? String(initialExperienceMonths)
                    : ""
              }
              onChange={markDirty}
            >
              <option value="">{EXPERIENCE_NOT_STATED}</option>
              {EXPERIENCE_MONTH_OPTIONS.map((months) => (
                <option key={months} value={String(months)}>
                  {experienceMonthsLabel(months)}
                </option>
              ))}
            </Select>
          </FieldBlock>
          <FieldBlock label="Phone">
            <PhoneField
              key={`phone-${prefillKey}`}
              name="phone"
              countryCodeName="phoneCountryCode"
              countryCode={prefill.phone ? null : initial?.phoneCountryCode}
              defaultValue={prefill.phone ?? initial?.phone}
              onValueChange={markDirty}
            />
          </FieldBlock>
          <Field label="Email">
            <TextInput
              name="email"
              type="email"
              pattern={EMAIL_PATTERN}
              title="name@example.com"
              value={email}
              aria-invalid={!!emailError}
              aria-describedby={emailError ? emailErrorId : undefined}
              onChange={(event) => setEmail(event.target.value)}
            />
            {emailError ? (
              <p id={emailErrorId} role="alert" className="text-xs text-error-600">
                {emailError}
              </p>
            ) : null}
          </Field>
          <DictatedTextArea
            name="address"
            label="Address"
            defaultValue={initial?.address ?? ""}
            explainWhenUnavailable={false}
            onDirty={markDirty}
          />
          <RichTextField
            name="notes"
            label="Notes"
            defaultValue={initial?.notes ?? ""}
            join="paragraph"
            className="md:col-span-2"
            onDirty={markDirty}
            explainWhenUnavailable={false}
          />
          <div
            role="group"
            aria-labelledby={identityLabelId}
            className="grid gap-3 rounded-lg border border-line-200 bg-surface-50 p-3 md:col-span-2 md:grid-cols-3"
          >
            <div className="md:col-span-3">
              <h3 id={identityLabelId} className="field-label">
                Identity
              </h3>
              <p className="mt-0.5 text-xs text-ink-muted">
                Masked on every shared or exported view.
                {aadhaarRequired ? "" : " Optional for this older record — add only with the artisan’s consent."}
              </p>
            </div>
            <AadhaarField
              defaultValue={initial?.aadhaarNumber}
              excludeArtisanId={initial?.id ?? null}
              required={aadhaarRequired}
              offerCardCapture
              onUseExisting={onUseExisting}
              onValueChange={markDirty}
            />
            <PehchanFields
              key={`pehchan-${prefillKey}`}
              initialAvailable={prefill.pehchanCardAvailable ?? (initial ? (initial.pehchanCardAvailable ?? true) : null)}
              initialNumber={prefill.pehchanCardNumber ?? initial?.pehchanCardNumber}
              onDirty={markDirty}
            />
            {initial?.id ? <ArtisanConsentToggle artisanId={initial.id} /> : null}
            {initial?.id ? (
              <CrossPortalLinkPanel artisanId={initial.id} onRefreshed={takeRefreshedArtisan} />
            ) : (
              <CrossPortalFind
                readNumbers={typedIdentityNumbers}
                onFill={fillFromFieldRepository}
                filled={crossMatch}
                cardInput={fromCard || null}
              />
            )}
          </div>
          <DosDontsField
            name="dos"
            fieldKey="dos"
            label="Do's"
            helper="One point per line."
            defaultValue={initial?.dos}
          />
          <DosDontsField
            name="donts"
            fieldKey="donts"
            label="Don'ts"
            helper="One point per line."
            defaultValue={initial?.donts}
          />
          <StatusField canSetStatus={canSetStatus} initialStatus={initial?.status} onDirty={markDirty} />
        </div>
        {initial ? (
          <ExistingMedia linkedRecordType="artisan" linkedRecordId={initial.id} recordHold={filing.stored} />
        ) : null}
        <div data-field="photo" className="min-w-0">
          <MediaCaptureField
            files={mediaFiles}
            onFilesChange={(files) => {
              setMediaFiles(files);
              markDirty();
            }}
            title="Artisan media"
            description="Photos, audio, video and documents."
            artisanIds={initial?.id ? [initial.id] : undefined}
          />
        </div>
        <div data-field="location" className="min-w-0">
          <LocationFields
            initial={initialLocation}
            onDirty={markDirty}
            subjectLabel="the artisan"
            statedPlace={initial?.place}
            statedPrefill={statedPrefill}
          />
        </div>
        {uploadProgress ? <UploadProgress progress={uploadProgress} files={mediaFiles} /> : null}
        {footerFields ? <div className="grid gap-3 border-t border-line-200 pt-4">{footerFields}</div> : null}
        <HeldPostNotice refusal={filing.notice} id={filingNoticeId} className="" sayPending />
        <ActionGroup align="end">
          <button type="button" className="field-button-secondary" onClick={handleBack}>
            Cancel
          </button>
          <button
            className="field-button"
            disabled={saving || checkingDuplicate || filing.saveHeld}
            aria-describedby={filing.notice ? filingNoticeId : undefined}
          >
            {checkingDuplicate ? "Checking..." : saving ? "Saving..." : initial ? "Update artisan" : "Save artisan"}
          </button>
        </ActionGroup>
      </form>
      <PossibleDuplicateDialog
        open={duplicateHold !== null}
        message={duplicateHold?.message ?? null}
        candidates={duplicateHold?.candidates ?? []}
        saving={saving}
        onOpen={(candidate) => {
          setDuplicateHold(null);
          openCandidate(candidate);
        }}
        onKeepEditing={() => setDuplicateHold(null)}
        onDifferent={() => {
          const ids = (duplicateHold?.candidates ?? []).map((candidate) => candidate.artisan.id);
          setDuplicateHold(null);
          setAside(ids);
          setTimeout(() => formRef.current?.requestSubmit(), 0);
        }}
      />
      <DuplicateArtisanDialog
        open={duplicatePromptOpen}
        artisan={conflict?.existingArtisan}
        message={conflict?.message}
        maskedValue={conflict?.maskedValue}
        onOpenExisting={() => {
          setDuplicatePromptOpen(false);
          resetDirty();
          if (!conflict?.existingArtisan) return;
          if (onUseExisting) {
            onUseExisting(conflict.existingArtisan);
            return;
          }
          router.push(`/artisans/${conflict.existingArtisan.id}/edit`);
        }}
        onDiscard={discardEntry}
        onKeepEditing={() => setDuplicatePromptOpen(false)}
      />
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
