"use client";

import { CalendarField } from "@/components/ui/CalendarField";
import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { IdCard, Trash2 } from "lucide-react";

import { StoredMediaImage } from "@/components/designers/StoredMediaImage";
import {
  DESIGNER_EXPERIENCE_BOXES,
  DESIGNER_PROFILE_COPY_NOTICE,
  DESIGNER_PROFILE_GROUPS,
  DESIGNER_PROFILE_HELP,
  DESIGNER_PROFILE_LABELS,
  isDesignerProfileFieldRequired,
  type DesignerProfileGroupKey
} from "@/components/designers/profileCopy";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { RichTextField } from "@/components/richtext/RichTextField";
import { Field, Select, TextInput } from "@/components/FormControls";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { DocumentPreview } from "@/components/media/DocumentPreview";
import { PhoneField } from "@/components/forms/PhoneField";
import {
  LocationFields,
  OFFLINE_STATES,
  loadAddressReference,
  pincodeValidationError,
  postalZoneMismatch
} from "@/components/forms/LocationFields";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { handleFormEnter } from "@/lib/formNav";
import { locationFromForm, useUnsavedChanges } from "@/lib/forms";
import { uploadMediaBatch } from "@/lib/media";
import {
  DESIGNER_PROFILE_MEDIA_RECORD_TYPE,
  fullDesignerProfileBody,
  type DesignerProfile,
  type DesignerProfileField,
  type DesignerProfileUpdateBody
} from "@/lib/designers";
import type { AddressReference } from "@/lib/types";

const MAX = {
  displayName: 180,
  localName: 180,
  designation: 180,
  institution: 180,
  department: 180,
  qualification: 220,
  specialisation: 220,
  biography: 20000,
  phone: 40,
  website: 300,
  addressLine: 300,
  city: 120,
  empanelmentNo: 120
} as const;

const EXPERIENCE_UNANSWERED = "Not recorded";

const EXPERIENCE_YEAR_OPTIONS = Array.from({ length: 71 }, (_, index) => index);
const EXPERIENCE_MONTH_OPTIONS = Array.from({ length: 12 }, (_, index) => index);

export function DesignerProfileForm({
  profile,
  save,
  onSaved,
  possessive = "your"
}: {
  profile: DesignerProfile;
  save: (body: DesignerProfileUpdateBody) => Promise<DesignerProfile>;
  onSaved: (next: DesignerProfile) => void;
  possessive?: string;
}) {
  const router = useRouter();
  const { dirty, markDirty, resetDirty } = useUnsavedChanges();
  const [backPromptOpen, setBackPromptOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [stateName, setStateName] = useState(profile.state ?? "");
  const [pincode, setPincode] = useState((profile.pincode ?? "").replace(/\D/g, "").slice(0, 6));
  const [reference, setReference] = useState<AddressReference | null>(null);

  const [photoId, setPhotoId] = useState(profile.photoMediaId);
  const [signatureId, setSignatureId] = useState(profile.signatureMediaId);
  const [cvId, setCvId] = useState(profile.cvMediaId);
  const [photoFiles, setPhotoFiles] = useState<File[]>([]);
  const [signatureFiles, setSignatureFiles] = useState<File[]>([]);
  const [cvFiles, setCvFiles] = useState<File[]>([]);

  const formRef = useRef<HTMLFormElement | null>(null);

  useLeaveGuard(dirty, () => setBackPromptOpen(true));

  useEffect(() => {
    let live = true;
    loadAddressReference()
      .then((payload) => {
        if (live) setReference(payload);
      })
      .catch(() => {
      });
    return () => {
      live = false;
    };
  }, []);

  const stateOptions = useMemo(() => {
    const served = reference?.statesAndUnionTerritories ?? OFFLINE_STATES;
    return stateName && !served.includes(stateName) ? [stateName, ...served] : served;
  }, [reference, stateName]);

  const pincodeProblem = pincodeValidationError(pincode);
  const zoneProblem = pincodeProblem ? null : postalZoneMismatch(stateName, pincode);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);

    setSaving(true);
    setError(null);
    setNotice(null);
    const troubles: string[] = [];

    try {
      let nextPhotoId = photoId;
      let nextSignatureId = signatureId;
      let nextCvId = cvId;

      let strandedPhotos: File[] = [];
      let strandedSignatures: File[] = [];
      let strandedCvs: File[] = [];

      if (photoFiles.length) {
        const attempt = await uploadOne(
          photoFiles,
          profile.userId,
          "Designer photograph",
          "designer photograph",
          troubles
        );
        if (attempt.mediaId) nextPhotoId = attempt.mediaId;
        strandedPhotos = attempt.stranded;
      }
      if (signatureFiles.length) {
        const attempt = await uploadOne(
          signatureFiles,
          profile.userId,
          "Designer signature",
          "designer signature",
          troubles
        );
        if (attempt.mediaId) nextSignatureId = attempt.mediaId;
        strandedSignatures = attempt.stranded;
      }
      if (cvFiles.length) {
        const attempt = await uploadOne(cvFiles, profile.userId, "Designer CV", "designer CV", troubles);
        if (attempt.mediaId) nextCvId = attempt.mediaId;
        strandedCvs = attempt.stranded;
      }

      const body = fullDesignerProfileBody({
        displayName: text(form, "displayName"),
        localName: text(form, "localName"),
        designation: text(form, "designation"),
        institution: text(form, "institution"),
        department: text(form, "department"),
        qualification: text(form, "qualification"),
        specialisation: text(form, "specialisation"),
        experienceYears: text(form, "experienceYears"),
        experienceMonths: text(form, "experienceMonths"),
        biography: text(form, "biography"),
        phone: text(form, "phone"),
        email: text(form, "email"),
        website: text(form, "website"),
        addressLine: text(form, "addressLine"),
        city: text(form, "city"),
        state: stateName,
        pincode,
        photoMediaId: nextPhotoId,
        signatureMediaId: nextSignatureId,
        cvMediaId: nextCvId,
        empanelmentNo: text(form, "empanelmentNo"),
        empanelmentDate: text(form, "empanelmentDate"),
        location: locationFromForm(form)
      });

      const saved = await save(body);
      setPhotoId(saved.photoMediaId);
      setSignatureId(saved.signatureMediaId);
      setCvId(saved.cvMediaId);
      setStateName(saved.state ?? "");
      setPincode((saved.pincode ?? "").replace(/\D/g, "").slice(0, 6));
      setPhotoFiles(strandedPhotos);
      setSignatureFiles(strandedSignatures);
      setCvFiles(strandedCvs);
      resetDirty();
      if (strandedPhotos.length || strandedSignatures.length || strandedCvs.length) {
        markDirty();
        troubles.push("Press Save to retry.");
      }
      setBackPromptOpen(false);
      onSaved(saved);
      setNotice(
        troubles.length
          ? `Profile saved. ${troubles.join(" ")}`
          : "Profile saved."
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save this profile");
    } finally {
      setSaving(false);
    }
  }

  const groupBody: Record<DesignerProfileGroupKey, React.ReactNode> = {
    identity: (
      <>
        <DictatedField
          name="displayName"
          label={DESIGNER_PROFILE_LABELS.displayName}
          defaultValue={profile.displayName ?? ""}
          maxLength={MAX.displayName}
          required={isDesignerProfileFieldRequired("displayName")}
          onDirty={markDirty}
        />
        <DictatedField
          name="localName"
          label={DESIGNER_PROFILE_LABELS.localName}
          defaultValue={profile.localName ?? ""}
          maxLength={MAX.localName}
          onDirty={markDirty}
        />
        <DictatedField
          name="designation"
          label={DESIGNER_PROFILE_LABELS.designation}
          defaultValue={profile.designation ?? ""}
          maxLength={MAX.designation}
          onDirty={markDirty}
        />
      </>
    ),
    institution: (
      <>
        <DictatedField
          name="institution"
          label={DESIGNER_PROFILE_LABELS.institution}
          defaultValue={profile.institution ?? ""}
          maxLength={MAX.institution}
          onDirty={markDirty}
        />
        <DictatedField
          name="department"
          label={DESIGNER_PROFILE_LABELS.department}
          defaultValue={profile.department ?? ""}
          maxLength={MAX.department}
          onDirty={markDirty}
        />
      </>
    ),
    qualifications: (
      <>
        <DictatedField
          name="qualification"
          label={DESIGNER_PROFILE_LABELS.qualification}
          defaultValue={profile.qualification ?? ""}
          maxLength={MAX.qualification}
          required={isDesignerProfileFieldRequired("qualification")}
          onDirty={markDirty}
        />
        <DictatedField
          name="specialisation"
          label={DESIGNER_PROFILE_LABELS.specialisation}
          defaultValue={profile.specialisation ?? ""}
          maxLength={MAX.specialisation}
          onDirty={markDirty}
        />
        <FieldBlock label={DESIGNER_EXPERIENCE_BOXES.group}>
          <div className="grid grid-cols-2 gap-2">
            <div className="grid min-w-0 gap-1">
              <span className="text-xs font-medium text-ink-500">{DESIGNER_EXPERIENCE_BOXES.years.label}</span>
              <Select
                name="experienceYears"
                aria-label={DESIGNER_EXPERIENCE_BOXES.years.ariaLabel}
                defaultValue={String(profile.experienceYears ?? "")}
                onChange={markDirty}
              >
                <option value="">{EXPERIENCE_UNANSWERED}</option>
                {EXPERIENCE_YEAR_OPTIONS.map((years) => (
                  <option key={years} value={years}>
                    {years}
                  </option>
                ))}
              </Select>
            </div>
            <div className="grid min-w-0 gap-1">
              <span className="text-xs font-medium text-ink-500">{DESIGNER_EXPERIENCE_BOXES.months.label}</span>
              <Select
                name="experienceMonths"
                aria-label={DESIGNER_EXPERIENCE_BOXES.months.ariaLabel}
                defaultValue={String(profile.experienceMonths ?? "")}
                onChange={markDirty}
              >
                <option value="">{EXPERIENCE_UNANSWERED}</option>
                {EXPERIENCE_MONTH_OPTIONS.map((months) => (
                  <option key={months} value={months}>
                    {months}
                  </option>
                ))}
              </Select>
            </div>
          </div>
        </FieldBlock>
      </>
    ),
    biography: (
      <div className="md:col-span-2">
        <DictatedTextArea
          name="biography"
          label={DESIGNER_PROFILE_LABELS.biography}
          defaultValue={profile.biography ?? ""}
          maxLength={MAX.biography}
          rows={8}
          explainWhenUnavailable={false}
          onDirty={markDirty}
        />
      </div>
    ),
    contact: (
      <>
        <FieldBlock label={DESIGNER_PROFILE_LABELS.phone} required={isDesignerProfileFieldRequired("phone")}>
          <PhoneField
            name="phone"
            countryCode={profile.phoneCountryCode}
            defaultValue={profile.phone}
            onValueChange={markDirty}
            required={isDesignerProfileFieldRequired("phone")}
          />
        </FieldBlock>
        <Field label={DESIGNER_PROFILE_LABELS.email} required={isDesignerProfileFieldRequired("email")}>
          <TextInput
            name="email"
            type="email"
            defaultValue={profile.email ?? ""}
            required={isDesignerProfileFieldRequired("email")}
          />
        </Field>
        <Field label={DESIGNER_PROFILE_LABELS.website}>
          <TextInput name="website" type="url" defaultValue={profile.website ?? ""} maxLength={MAX.website} />
        </Field>
      </>
    ),
    address: (
      <>
        <RichTextField
          name="addressLine"
          label={DESIGNER_PROFILE_LABELS.addressLine}
          defaultValue={profile.addressLine ?? ""}
          maxLength={MAX.addressLine}
          className="md:col-span-2"
          explainWhenUnavailable={false}
          onDirty={markDirty}
        />
        <DictatedField
          name="city"
          label={DESIGNER_PROFILE_LABELS.city}
          defaultValue={profile.city ?? ""}
          maxLength={MAX.city}
          onDirty={markDirty}
        />
        <FieldBlock label={DESIGNER_PROFILE_LABELS.state}>
          <Select
            name="profileState"
            value={stateName}
            onChange={(event) => {
              setStateName(event.target.value);
              markDirty();
            }}
          >
            <option value="">Select state</option>
            {stateOptions.map((entry) => (
              <option key={entry}>{entry}</option>
            ))}
          </Select>
        </FieldBlock>
        <Field label={DESIGNER_PROFILE_LABELS.pincode}>
          <TextInput
            name="profilePincode"
            inputMode="numeric"
            maxLength={6}
            value={pincode}
            aria-invalid={Boolean(pincodeProblem)}
            onChange={(event) => setPincode(event.target.value.replace(/\D/g, "").slice(0, 6))}
          />
        </Field>
        {pincodeProblem || zoneProblem ? (
          <p className={`md:col-span-2 text-xs leading-5 ${pincodeProblem ? "text-error-600" : "text-amber-800 dark:text-amber-100"}`}>
            {pincodeProblem ?? zoneProblem}
          </p>
        ) : null}
        <div className="min-w-0 md:col-span-2">
          <p className="mb-3 text-sm leading-6 text-ink-muted">
            District, village and map point. Fill in the postal address above as well.
          </p>
          <LocationFields initial={profile.location ?? null} onDirty={markDirty} subjectLabel="the designer" />
        </div>
      </>
    ),
    empanelment: (
      <>
        <Field label={DESIGNER_PROFILE_LABELS.empanelmentNo} required>
          <TextInput
            name="empanelmentNo"
            defaultValue={profile.empanelmentNo ?? ""}
            maxLength={MAX.empanelmentNo}
            required={!profile.empanelmentNoMissing}
          />
        </Field>
        {profile.empanelmentNo && profile.signInByEmpanelmentNo === false ? (
          <p className="text-xs leading-5 text-amber-800 dark:text-amber-100 md:col-span-2">
            Another designer uses this number. Sign in with your email.
          </p>
        ) : null}
        <div className="grid min-w-0 gap-1">
          <CalendarField
            id="designer-empanelment-date"
            label={DESIGNER_PROFILE_LABELS.empanelmentDate}
            name="empanelmentDate"
            defaultValue={(profile.empanelmentDate ?? "").slice(0, 10)}
            onChange={markDirty}
          />
        </div>
      </>
    ),
    images: (
      <>
        <div className="min-w-0 md:col-span-2">
          <MediaSlot
            label={DESIGNER_PROFILE_LABELS.photoMediaId}
            help={DESIGNER_PROFILE_HELP.photoMediaId}
            mediaId={photoId}
            files={photoFiles}
            frameClassName="h-32 w-32"
            alt="Photograph of the designer"
            onFilesChange={(files) => {
              setPhotoFiles(files.slice(-1));
              markDirty();
            }}
            onRemove={() => {
              setPhotoId(null);
              setPhotoFiles([]);
              markDirty();
            }}
          />
        </div>
        <div className="min-w-0 md:col-span-2">
          <MediaSlot
            label={DESIGNER_PROFILE_LABELS.signatureMediaId}
            help={DESIGNER_PROFILE_HELP.signatureMediaId}
            mediaId={signatureId}
            files={signatureFiles}
            frameClassName="h-20 w-56"
            alt="The designer’s signature"
            onFilesChange={(files) => {
              setSignatureFiles(files.slice(-1));
              markDirty();
            }}
            onRemove={() => {
              setSignatureId(null);
              setSignatureFiles([]);
              markDirty();
            }}
          />
        </div>
        <div className="min-w-0 md:col-span-2">
          <DocumentSlot
            label={DESIGNER_PROFILE_LABELS.cvMediaId}
            help={DESIGNER_PROFILE_HELP.cvMediaId}
            mediaId={cvId}
            files={cvFiles}
            onFilesChange={(files) => {
              setCvFiles(files.slice(-1));
              markDirty();
            }}
            onRemove={() => {
              setCvId(null);
              setCvFiles([]);
              markDirty();
            }}
          />
        </div>
      </>
    )
  };

  return (
    <>
      <div
        role="alert"
        aria-live="assertive"
        className={error ? "mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700" : "sr-only"}
      >
        {error}
      </div>
      <div
        role="status"
        aria-live="polite"
        className={
          notice ? "mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700" : "sr-only"
        }
      >
        {notice}
      </div>

      {profile.empanelmentNoMissing ? (
        <p className="mb-4 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-900">
          Add your empanelment number — it is required on every report.
        </p>
      ) : null}

      <p className="mb-5 rounded-md border border-line-200 bg-surface-50 px-4 py-3 text-sm leading-6 text-ink-muted">
        {DESIGNER_PROFILE_COPY_NOTICE}
      </p>

      <form ref={formRef} onSubmit={submit} onInput={markDirty} onKeyDown={handleFormEnter} className="grid gap-5">
        <DictationUnavailableNotice />
        {DESIGNER_PROFILE_GROUPS.map((group) => (
          <section key={group.title} className="panel grid gap-3 p-4">
            <div>
              <h2 className="font-display text-lg font-bold text-ink-900">{group.title}</h2>
              {group.blurb ? <p className="mt-1 text-sm leading-6 text-ink-muted">{group.blurb}</p> : null}
            </div>
            <div className="grid gap-3 md:grid-cols-2">{groupBody[group.key]}</div>
            <HelpLines fields={group.fields} />
          </section>
        ))}

        <div className="flex flex-wrap items-center gap-2">
          <button className="field-button" disabled={saving}>
            {saving ? "Saving…" : `Save ${possessive} profile`}
          </button>
          {dirty ? <span className="text-xs text-ink-500">Unsaved changes</span> : null}
        </div>
      </form>

      <UnsavedChangesDialog
        open={backPromptOpen}
        saving={saving}
        onKeepEditing={() => setBackPromptOpen(false)}
        onDiscard={() => {
          setBackPromptOpen(false);
          resetDirty();
          router.back();
        }}
        onSave={() => {
          setBackPromptOpen(false);
          formRef.current?.requestSubmit();
        }}
      />
    </>
  );
}

function DictatedField({
  name,
  label,
  defaultValue,
  maxLength,
  required,
  onDirty
}: {
  name: string;
  label: string;
  defaultValue: string;
  maxLength: number;
  required?: boolean;
  onDirty: () => void;
}) {
  const [value, setValue] = useState(defaultValue);

  return (
    <DictatedTextInput
      name={name}
      label={label}
      required={required}
      maxLength={maxLength}
      explainWhenUnavailable={false}
      value={value}
      onChange={(next) => {
        setValue(next);
        onDirty();
      }}
    />
  );
}

function HelpLines({ fields }: { fields: DesignerProfileField[] }) {
  const lines = fields.flatMap((field) => {
    const help = DESIGNER_PROFILE_HELP[field];
    return help ? [{ field, help }] : [];
  });
  if (!lines.length) return null;
  return (
    <ul className="grid gap-1 text-xs leading-5 text-ink-500">
      {lines.map((line) => (
        <li key={line.field}>
          <span className="font-medium text-ink-700">{DESIGNER_PROFILE_LABELS[line.field]}:</span> {line.help}
        </li>
      ))}
    </ul>
  );
}

function MediaSlot({
  label,
  help,
  mediaId,
  files,
  alt,
  frameClassName,
  onFilesChange,
  onRemove
}: {
  label: string;
  help?: string;
  mediaId: string | null;
  files: File[];
  alt: string;
  frameClassName: string;
  onFilesChange: (files: File[]) => void;
  onRemove: () => void;
}) {
  return (
    <div className="grid gap-3">
      <div className="flex flex-wrap items-start gap-4">
        <div>
          <span className="field-label">{label}</span>
          <div className="mt-1">
            {mediaId ? (
              <StoredMediaImage mediaId={mediaId} alt={alt} className={frameClassName} />
            ) : (
              <div className={`grid ${frameClassName} place-items-center rounded-md border border-dashed border-line-200 bg-surface-50 p-2 text-center text-xs leading-4 text-ink-500`}>
                <IdCard className="h-4 w-4" aria-hidden />
                Nothing on file
              </div>
            )}
          </div>
        </div>
        {mediaId ? (
          <button type="button" className="field-button-secondary mt-6" onClick={onRemove}>
            <Trash2 className="h-4 w-4" aria-hidden />
            Remove
          </button>
        ) : null}
      </div>
      <MediaCaptureField
        files={files}
        onFilesChange={onFilesChange}
        title={mediaId ? `Replace ${label.toLowerCase()}` : `Attach ${label.toLowerCase()}`}
        description={
          help ??
          "One image. Save the profile to keep it."
        }
        allowedTypes={["IMAGE"]}
        allowDocuments={false}
      />
    </div>
  );
}

function DocumentSlot({
  label,
  help,
  mediaId,
  files,
  onFilesChange,
  onRemove
}: {
  label: string;
  help?: string;
  mediaId: string | null;
  files: File[];
  onFilesChange: (files: File[]) => void;
  onRemove: () => void;
}) {
  return (
    <div className="grid gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="field-label">{label}</span>
        {mediaId ? (
          <button type="button" className="field-button-secondary" onClick={onRemove}>
            <Trash2 className="h-4 w-4" aria-hidden />
            Remove
          </button>
        ) : null}
      </div>
      <DocumentPreview mediaId={mediaId} noun={label} className="h-120" />
      <MediaCaptureField
        files={files}
        onFilesChange={onFilesChange}
        title={mediaId ? `Replace ${label}` : `Attach ${label}`}
        description={
          help ??
          "One document. Save the profile to keep it."
        }
        allowedTypes={["PDF", "DOCUMENT", "IMAGE"]}
        accept=".pdf,.doc,.docx,.odt,image/*"
        allowDocuments
      />
    </div>
  );
}

function text(form: FormData, key: string): string {
  const value = form.get(key);
  return typeof value === "string" ? value.trim() : "";
}

type OneUpload = {
  mediaId: string | null;
  stranded: File[];
};

async function uploadOne(
  files: File[],
  userId: string,
  caption: string,
  subject: string,
  troubles: string[]
): Promise<OneUpload> {
  try {
    const { outcomes } = await uploadMediaBatch({
      files,
      linkedRecordType: DESIGNER_PROFILE_MEDIA_RECORD_TYPE,
      linkedRecordId: userId,
      caption,
    });
    const stranded = outcomes.filter((outcome) => outcome.failure !== null);
    if (stranded.length) {
      troubles.push(
        `The ${subject} did not upload (${stranded
          .map((outcome) => outcome.file.name)
          .join(", ")}). The old one was kept.`
      );
    }
    return {
      mediaId: outcomes.find((outcome) => outcome.media !== null)?.media?.id ?? null,
      stranded: stranded.map((outcome) => outcome.file)
    };
  } catch (err) {
    troubles.push(
      `The ${subject} didn't upload (${err instanceof Error ? err.message : "transfer failed"}). The old one was kept.`
    );
    return { mediaId: null, stranded: files };
  }
}
