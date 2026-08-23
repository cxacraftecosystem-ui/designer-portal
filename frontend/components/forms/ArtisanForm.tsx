"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { useAuth } from "@/components/AuthProvider";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { LIST_PAGE_CEILING, listCut, mergeById, type ListCut } from "@/components/data/cappedList";
import { CarryForwardCards } from "@/components/CarryForwardCards";
import { Field, Select, TextInput } from "@/components/FormControls";
import { AadhaarField, aadhaarValidationError, isMaskedIdentityNumber } from "@/components/forms/AadhaarField";
import { IdentityCardCapture } from "@/components/forms/IdentityCardCapture";
import { CarryContextBanner, carryScope, useCarryContext, type CarryScopeState } from "@/components/forms/CarryContextBanner";
import { DateField } from "@/components/forms/DateTimeField";
import { DosDontsField } from "@/components/forms/DosDontsField";
import { DuplicateArtisanDialog } from "@/components/forms/DuplicateArtisanDialog";
import type { InlineHostSeed, InlineRecordSurfaceProps, UseExistingArtisan } from "@/components/forms/inlineRecordHost";
import { LocationFields, type LocationInitialValues } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { PhoneField } from "@/components/forms/PhoneField";
import { TitleCasedInput } from "@/components/forms/TitleCasedInput";
import { useRecordOffPage } from "@/components/forms/recordPickers";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { UploadProgress } from "@/components/media/UploadProgress";
import { RecordCodeCard } from "@/components/RecordCode";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { RichTextField } from "@/components/richtext/RichTextField";
import { appendStoredParagraph } from "@/components/richtext/storedRichText";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { ApiError, apiFetch, buildQuery, listResource } from "@/lib/api";
import { locationFromForm, recordedAtFromForm, recordedTimezoneFromForm, requiredText, textValue, useUnsavedChanges } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { collectExifMetadata, exifMetadataToRemark, uploadMediaBatch, type BatchProgress } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { hasRank } from "@/lib/permissions";
import { deriveAge, deriveExperienceYears } from "@/lib/recordDerivations";
import type { AadhaarLookupResult, Artisan, ArtisanIdentityConflict, ArtisanIdentityMatch, Craft, RecordStatus } from "@/lib/types";

// Android parity (MainActivity.kt genderOptions).
const genderOptions = ["Male", "Female", "Transgender", "Other"];

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// The Pehchan Yes/No dropdown submits these through the Select's mirror input; `submit` parses them
// back into the boolean the API expects. Keeping them as the option VALUES (with "Yes"/"No" only as
// labels) means the payload never depends on how the question happens to be worded on screen.
const PEHCHAN_YES = "true";
const PEHCHAN_NO = "false";

/**
 * The one combination the API refuses outright, worded exactly as Android and the server word it.
 * The browser's own "Please fill out this field." says nothing about the way OUT of the problem
 * (flip the answer to No), which is the half a researcher without a card in hand actually needs.
 */
const PEHCHAN_NUMBER_REQUIRED =
  "Enter the Artisan Pehchan Card number, or set the card to 'No' if the artisan does not hold one.";

/** The `detail` of an API error response, whatever shape the server chose for it. */
function errorDetail(error: unknown): unknown {
  if (!(error instanceof ApiError)) return null;
  const payload = error.payload;
  if (!payload || typeof payload !== "object" || !("detail" in payload)) return null;
  return (payload as { detail: unknown }).detail;
}

/**
 * The sentence the server actually wrote, dug back out of the response body.
 *
 * `apiFetch` builds `ApiError.message` with `String(detail)`, which is right for the plain-string
 * details most routes raise and useless for the two structured ones the identity fields produce: a
 * 409 whose detail is a conflict object, and FastAPI's 422 whose detail is a list of field errors.
 * Both stringify to "[object Object]", and a mistyped Aadhaar is exactly the case where the specific
 * message ("that number fails its checksum") is the entire value of the response.
 */
function readableError(error: unknown, fallback: string): string {
  const detail = errorDetail(error);
  if (typeof detail === "string" && detail.trim()) return detail;
  if (Array.isArray(detail)) {
    const messages = detail
      .map((entry) => (entry && typeof entry === "object" && "msg" in entry ? String((entry as { msg: unknown }).msg) : ""))
      // Pydantic prefixes every custom validator message with "Value error, "; the researcher only
      // needs the sentence after it.
      .map((message) => message.replace(/^Value error,\s*/, "").trim())
      .filter(Boolean);
    if (messages.length) return messages.join(" ");
  }
  if (detail && typeof detail === "object" && "message" in detail) {
    const message = String((detail as { message: unknown }).message);
    if (message.trim()) return message;
  }
  const message = error instanceof Error ? error.message : "";
  return message && message !== "[object Object]" ? message : fallback;
}

/** The identity conflict behind a 409, or null when the failure was something else entirely. */
function identityConflict(error: unknown): ArtisanIdentityConflict | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null;
  const detail = errorDetail(error);
  if (!detail || typeof detail !== "object") return null;
  const conflict = detail as ArtisanIdentityConflict;
  return conflict.code === "artisan_identity_conflict" ? conflict : null;
}

/**
 * The artisan who already holds `digits`, or null — the save-time half of the Aadhaar duplicate check.
 *
 * `AadhaarField` runs the same lookup as the number is typed and shows an inline warning, but a
 * warning three fields up the page is easy to type past. Asking again at submit is what turns it into
 * a decision (see :func:`DuplicateArtisanDialog`), and it costs one cheap request on a path that was
 * about to make an expensive one.
 *
 * Nothing here ever blocks on its own: a number that fails validation cannot match a stored (already
 * validated) Aadhaar, and a failed request means the server simply gets to answer the question itself
 * with its 409. Being offline must never stop a researcher saving.
 *
 * A MASK is skipped outright rather than left to fail validation. It means the number was not
 * changed, so the only artisan it could ever "match" is this one — and asking the server to look up
 * "XXXX XXXX 9012" would put a masked identifier in a query string for no answer at all.
 */
async function findArtisanByAadhaar(digits: string | null, excludeArtisanId: string | null): Promise<ArtisanIdentityMatch | null> {
  const number = (digits ?? "").trim();
  if (!number || isMaskedIdentityNumber(number) || aadhaarValidationError(number)) return null;
  try {
    const result = await apiFetch<AadhaarLookupResult>(`/artisans/lookup/aadhaar${buildQuery({ number })}`);
    const found = result.found ? (result.artisan ?? null) : null;
    return found && found.id !== excludeArtisanId ? found : null;
  } catch {
    return null;
  }
}

/**
 * "Does the artisan hold a Pehchan card?" and the card number, which only exist in one consistent
 * pair of states: Yes with a number, or No with nothing.
 *
 * Answering No clears the number rather than merely disabling the box — a disabled input is omitted
 * from FormData, so a stale number would survive invisibly in React state and reappear the moment
 * the answer flipped back to Yes. The API applies the same rule server-side (it forces the number to
 * null whenever availability is false); this is the UI half of that contract, so what the researcher
 * sees and what gets stored never disagree.
 *
 * `initialNumber` may be a MASK. `public_encode` runs the Pehchan number through the same
 * `mask_identity_number` as the Aadhaar, so a caller who is neither the artisan's own researcher nor
 * a professor upwards is handed "XXXX XXXX 3456" and may still edit the record. Editing the mask has
 * to be impossible: `validate_pehchan` accepts it (it is alphanumeric once the spaces come off and
 * comfortably inside 4-32 characters), so the API would have stored the literal "XXXXXXXX3456" over
 * a real card number — silently, with no error to notice, and then refused the next artisan who
 * genuinely holds that card on the unique index. The box is therefore read-only while it holds a
 * mask, and `submit` leaves the field out of the payload entirely rather than posting it back.
 */
function PehchanFields({
  initialAvailable,
  initialNumber,
  onDirty
}: {
  initialAvailable: boolean;
  initialNumber?: string | null;
  onDirty: () => void;
}) {
  const baseId = useId();
  const numberId = `${baseId}-pehchan-number`;
  const hintId = `${baseId}-pehchan-hint`;
  const [available, setAvailable] = useState(initialAvailable);
  const storedMask = isMaskedIdentityNumber(initialNumber) ? String(initialNumber).trim() : null;
  // Set when the editor chooses to type a new card number over one they were never shown.
  const [replacing, setReplacing] = useState(false);
  const [number, setNumber] = useState(storedMask ? "" : (initialNumber ?? ""));
  // The mask still standing in for the stored number: what to show, and what to submit. Dropped when
  // the answer is No, so the box does not keep displaying a card number under the line that says
  // this artisan holds no card — and a No that is saved clears the stored number as it always did.
  const keptMask = replacing || !available ? null : storedMask;

  return (
    <>
      <div className="grid content-start gap-1">
        <span className="field-label">Artisan Pehchan Card available</span>
        <Select
          name="pehchanCardAvailable"
          value={available ? PEHCHAN_YES : PEHCHAN_NO}
          aria-label="Artisan Pehchan Card available"
          onChange={(event) => {
            const next = event.target.value === PEHCHAN_YES;
            setAvailable(next);
            if (!next) setNumber("");
            // The themed Dropdown is a button, so it fires no native input event for the form's
            // onInput to catch: the dirty flag has to be raised by hand.
            onDirty();
          }}
        >
          <option value={PEHCHAN_YES}>Yes</option>
          <option value={PEHCHAN_NO}>No</option>
        </Select>
      </div>
      <div className="grid content-start gap-1">
        <label className="field-label" htmlFor={numberId}>
          Artisan Pehchan Card number{available ? " *" : ""}
        </label>
        <input
          id={numberId}
          name="pehchanCardNumber"
          className="field-input read-only:bg-surface-50 read-only:text-ink-500 disabled:cursor-not-allowed disabled:bg-surface-50 disabled:text-ink-500"
          type="text"
          autoComplete="off"
          placeholder={available ? "As printed on the card" : "No card on record"}
          value={keptMask ?? number}
          // A read-only box is exempt from constraint validation, which is the right answer here:
          // `required` must not demand a number the editor is not permitted to read.
          readOnly={Boolean(keptMask)}
          required={available}
          disabled={!available}
          aria-disabled={!available}
          aria-describedby={hintId}
          onInvalid={(event) => event.currentTarget.setCustomValidity(PEHCHAN_NUMBER_REQUIRED)}
          // The API stores card numbers upper-cased without separators; showing that as it is typed
          // keeps the box honest about what will actually be saved. Clearing the custom validity
          // here is what lets a corrected value submit — it survives until it is reset by hand.
          onChange={(event) => {
            event.currentTarget.setCustomValidity("");
            setNumber(event.currentTarget.value.toUpperCase());
          }}
        />
        <p id={hintId} className="text-xs text-ink-muted">
          {!available
            ? 'Disabled because this artisan holds no Pehchan card. Switch "available" to Yes to enter a number.'
            : keptMask
              ? "On file, but hidden from you: the full card number is shown only to the researcher who recorded this artisan and to professors upwards. Saving leaves it exactly as it is."
              : "The PM Vishwakarma artisan ID printed on the card."}
          {keptMask ? (
            <>
              {" "}
              <button
                type="button"
                className="font-semibold text-purple-700 underline"
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
                className="font-semibold text-purple-700 underline"
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
        {/* Only when the artisan holds a card. Offering to photograph a document the record has just
            said does not exist is an invitation to photograph SOMETHING — and for this class of data
            the wrong photograph being taken at all is the incident, not the wrong value stored.
            Android's ArtisanForm makes the same call in the same place. */}
        {available ? (
          <IdentityCardCapture
            kind="PEHCHAN"
            targetLabel="the Pehchan card number"
            currentValue={keptMask ?? number}
            aadhaarProblem={aadhaarValidationError}
            onUse={(value) => {
              // Stand the mask down for the same reason the Aadhaar field does: posting it back
              // means "unchanged", and the confirmed number would never be written.
              setReplacing(true);
              setNumber(value);
              // A programmatic set fires no input event, so the dirty flag is raised by hand — the
              // same rule every themed control in this form follows.
              onDirty();
            }}
          />
        ) : null}
      </div>
    </>
  );
}

/**
 * Status policy (backend-enforced; the UI mirrors it): professor+ may pick any status and new
 * records default to APPROVED; everyone below sees a locked chip — creations are forced to PENDING
 * and unauthorized status changes are silently dropped server-side on update.
 */
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
            <option key={status}>{status}</option>
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

export function ArtisanForm({
  initial,
  seed,
  footerFields,
  onCreated,
  onCancel,
  onDiscardAndLeave,
  onQueued,
  onUseExisting
}: {
  initial?: Artisan;
  /**
   * What the picker that opened this form already knows — see {@link InlineHostSeed}. Only the
   * workshop applies to an artisan: an artisan has no artisan of their own, and their craft is the
   * one thing the carry bag is genuinely right about.
   */
  seed?: InlineHostSeed;
  /** Back out without saving, without navigating — see `InlineRecordHostProps.onCancel`. */
  onCancel?: () => void;
  /** Banked in the outbox, no id to link — see `InlineRecordHostProps.onQueued`. */
  onQueued?: () => void;
  /**
   * The duplicate check found the artisan already in the repository: hand them back rather than
   * navigating to their edit page. See {@link UseExistingArtisan} for why only the id and the name
   * cross, and never the masked identity number beside them.
   */
  onUseExisting?: UseExistingArtisan;
  /**
   * Hand the saved record back instead of navigating, so this form can be mounted INSIDE a dialog.
   *
   * The design-workshop stages pick artisans, products, tools and processes from reference
   * dropdowns, and until now a designer who found the record missing had to leave the stage they
   * were half-way through filling, create it on its own page, and come back — losing their place
   * in a 22-stage record, in a room with the artisan standing in front of them. Mounting this same
   * form in a dialog is what removes that, and it has to be this form: a second, simpler "quick
   * create" would be a second answer to what an artisan record requires (the Aadhaar checksum, the
   * duplicate check, the mandatory location, the Do's and Don'ts) and the two would drift.
   *
   * When it is set, the form neither routes nor shows its own "saved" panel — the caller closes the
   * dialog and selects the record.
   */
  onCreated?: (record: Artisan) => void;
} & InlineRecordSurfaceProps) {
  const router = useRouter();
  const { user } = useAuth();
  const canSetStatus = hasRank(user, "PROFESSOR");
  const identityLabelId = `${useId()}-identity`;
  /*
   * Ids for the two date fields and the three hint paragraphs beneath them. Explicit rather than
   * left to `Field`, because neither date may be wrapped in one: `Field` is a `<label>`, a
   * `<DateField>` carries a "Open calendar" button, and a wrapping label folds that button's own
   * name into the input's — the box then announces itself as "Date of birth Open calendar". The
   * hints have to be OUTSIDE the label for the same reason, pointed at by `aria-describedby`: text
   * inside a `<label>` becomes part of the accessible name, so a two-sentence explanation would be
   * read out every time the field took focus.
   */
  const derivedFieldsId = useId();
  const dobId = `${derivedFieldsId}-dob`;
  const dobHintId = `${derivedFieldsId}-dob-hint`;
  const craftStartId = `${derivedFieldsId}-craft-start`;
  const craftStartHintId = `${derivedFieldsId}-craft-start-hint`;
  const experienceId = `${derivedFieldsId}-experience`;
  const experienceHintId = `${derivedFieldsId}-experience-hint`;
  const formRef = useRef<HTMLFormElement>(null);
  const [crafts, setCrafts] = useState<Craft[]>([]);
  // Controlled so the carried craft can land in it after the list arrives; a defaultValue is fixed
  // at first render and the crafts request has not answered by then.
  const [craftId, setCraftId] = useState(initial?.craftId ?? "");
  // "Can I see this craft?" and "is there any signal?" are different answers — see useCarryContext.
  const [craftListState, setCraftListState] = useState<CarryScopeState>("pending");
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [error, setError] = useState<string | null>(null);
  // A rejected duplicate is not a generic error: it names an existing artisan the researcher should
  // open instead, so it gets its own state and its own panel with a link.
  const [conflict, setConflict] = useState<ArtisanIdentityConflict | null>(null);
  // The panel above is a reminder that stays put; the dialog is the one-time question asked at the
  // moment of saving. Separate flags so dismissing the question does not erase the reminder.
  const [duplicatePromptOpen, setDuplicatePromptOpen] = useState(false);
  const [checkingDuplicate, setCheckingDuplicate] = useState(false);
  const [saving, setSaving] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  const [savedRecord, setSavedRecord] = useState<Artisan | null>(null);
  const [email, setEmail] = useState(initial?.email ?? "");
  /*
   * ── THE TWO DATES, IN REACT STATE RATHER THAN LEFT UNCONTROLLED ─────────────────────────────
   *
   * Every other date on this form used to be a `defaultValue` on an uncontrolled box, and these two
   * cannot be, because something on screen is computed from each of them: the age beside the
   * birthday and the years of experience beside the joining date. A `<DateField>` sets React state
   * when a day is picked out of the calendar grid and dispatches no input event at all, so a
   * readout reading the DOM would be one pick behind — and worse, would be right often enough that
   * nobody would notice it was ever wrong.
   *
   * Seeded from `initial` at mount, exactly as `email` above is: the edit routes fetch the record
   * before they mount this component, so a first-render seed is the whole of what is needed. Both
   * are reset in `discardEntry`, for the same reason `email` is.
   */
  const [dateOfBirth, setDateOfBirth] = useState(
    initial?.dateOfBirth ? String(initial.dateOfBirth).slice(0, 10) : ""
  );
  const [craftStartDate, setCraftStartDate] = useState(
    initial?.craftStartDate ? String(initial.craftStartDate).slice(0, 10) : ""
  );
  // Bumped to throw the form away and rebuild it ("Discard this entry"). Remounting is what clears
  // the state living inside the field components — the Aadhaar digits, the Pehchan pair, the notes
  // rows, the Do's/Don'ts lists — which no amount of `form.reset()` can reach.
  const [formKey, setFormKey] = useState(0);
  const { dirty: typedSinceMount, markDirty, resetDirty } = useUnsavedChanges();
  const [backPromptOpen, setBackPromptOpen] = useState(false);
  /**
   * WHICH EXIT IS WAITING ON THAT PROMPT — this form's own Cancel button, or the back arrow in the
   * page header. Both raise the same dialog, and until this flag existed both got the same answer.
   *
   * ── THE DEFECT ────────────────────────────────────────────────────────────────────────────
   * "Discard" ran `resetDirty()` and then `leave()`, and `leave()` is `onCancel` — which in the
   * design-workshop stage embed REMOUNTS THIS FORM IN PLACE, because that host is not a dialog and
   * has nowhere to go. So a designer pressed Back, was asked, answered Discard, lost everything
   * they had typed AND STAYED ON THE PAGE, with a second press of Back still needed to do the thing
   * they had asked for. In a dialog the same wiring reads correctly, because the dialog visibly
   * closes; that is why this went unnoticed until the form had a third host.
   *
   * ── WHY THE FLAG MARKS THE CANCEL BUTTON AND NOT THE ARROW ────────────────────────────────
   * The arrow's route into the prompt is `useLeaveGuard`, which is handed a bare `onBlocked`
   * callback and is registered once for the life of the mount — there is no per-press hook to set a
   * flag from. The Cancel button is a call site of this component's own, so it is the one that can
   * say who it is. It is cleared on every way OUT of the prompt, so "set" only ever describes the
   * prompt currently on screen.
   */
  const [promptFromCancel, setPromptFromCancel] = useState(false);
  /**
   * Whether there is unsaved work this form must ask about before it is abandoned.
   *
   * THERE IS DELIBERATELY NO "EMBEDDED, SO DO NOT PROMPT" FLAG. The argument is written out in full
   * in `inlineRecordHost.ts`'s header, and it is worth knowing here because the flag looks obviously
   * right: the design-workshop stage page dropped its own unsaved-changes prompt on the grounds that
   * every stage edit lands in a durable IndexedDB draft, so nothing is lost by leaving. That
   * durability is a property of the STAGE's fields. It is not a property of this form's — the boxes
   * here live in React state and in uncontrolled DOM and are read only at submit — so a host that
   * suppressed the question would be discarding real work in silence, which is the one case that
   * page's own reasoning reserves the prompt for. Make these fields durable first, or keep asking.
   */
  const dirty = typedSinceMount;
  // Hands the prompt to the round back control in the page header, which is now the only back
  // control on the page.
  useLeaveGuard(dirty, () => setBackPromptOpen(true));
  // The API includes the record's stored location (not yet in the Artisan TS type); pass it so the
  // edit form pre-fills coordinates instead of auto-capturing the editor's current position.
  const initialLocation = initial
    ? ((initial as Artisan & { location?: LocationInitialValues | null }).location ?? null)
    : undefined;
  /**
   * Aadhaar is what stops one artisan becoming two records, so a NEW artisan must come with one.
   * An artisan documented before that rule has none, and a researcher who opened the record to fix a
   * phone number must not have to invent a government ID to save the correction — so on edit it is
   * required only when the record already carries one, where the requirement costs nothing and also
   * stops a stored number being quietly emptied. A masked number counts as carrying one — it is a
   * real number this caller may not read — and AadhaarField satisfies the requirement by posting the
   * mask straight back.
   */
  const aadhaarRequired = !initial || Boolean(initial.aadhaarNumber?.trim());
  // The workshop this artisan was documented at: shared picker, shared most-recent defaulting, and
  // the late-submission gate (see components/forms/WorkshopSelect).
  const workshop = useWorkshopSelection({
    /*
      THE SEED IS THE DESIGN WORKSHOP'S OWN LINKED WORKSHOP, and it outranks both the "most recent
      workshop" probe and the carry bag — an artisan created from stage 3 of a workshop was in THAT
      room, and a WORKSHOP-scoped picker narrows on exactly this column, so a seedless create files
      a person the picker that made them can never show. Passing it as `initialWorkshopId` also
      marks the selection `touched`, which is what stops the probe and `carry.onApply` below
      overwriting it a moment later.

      Absent — an unlinked design workshop — and everything behaves exactly as it did.
    */
    initialWorkshopId: initial?.workshopId ?? seed?.workshopId,
    isEdit: Boolean(initial),
    resetKey: initial?.id ?? null
  });

  const emailError =
    email.trim() && !EMAIL_RE.test(email.trim()) ? "Enter a valid email address (name@example.com)." : null;
  const emailErrorId = `${identityLabelId}-email-error`;

  /*
   * ── WHAT THE TWO DATES COME TO TODAY, BY THE SERVER'S OWN RULE ──────────────────────────────
   *
   * `lib/recordDerivations` is a PORT of `records.derive_age` and `records.derive_experience_years`,
   * not a second opinion about either — read its header before changing anything here. The rule is
   * declared once, on the server, because the server is what the workshop and the report read; this
   * form shows the same answer so a researcher can check the number they are about to cause before
   * they cause it. A form that computed its own age would be a figure the save then disagreed with,
   * which is worse than showing none, because the researcher has already read it.
   *
   * `todayIso` is the UTC calendar date, which is what the derivations use as their reference day
   * and what the previous `<input type="date">` used as its `max`. Nobody is born tomorrow, and
   * nobody took up a craft tomorrow either.
   */
  const todayIso = new Date().toISOString().slice(0, 10);
  const derivedAge = deriveAge(dateOfBirth);
  const derivedExperience = deriveExperienceYears(craftStartDate);

  /**
   * The craft dropdown, and what it is NOT showing — see `components/data/cappedList`.
   *
   * `pageSize` is clamped to 100 server-side and `/crafts` orders NAME ASCENDING (deliberately, see
   * the ordering comment in `routes/crafts.py`), so this is the first hundred crafts of the alphabet
   * out of 178 on this database (counted 2026-08-15) — not the newest hundred, which matters,
   * because the cut is stable and always falls in the same place. A craft whose name sorts past it
   * is unreachable in this REQUIRED picker, and the field beside it offers "Or new craft name",
   * which is exactly the wrong thing to reach for: the researcher creates a second craft row for a
   * craft the taxonomy already holds. Saying the list is cut is what stops that.
   */
  const [craftCut, setCraftCut] = useState<ListCut | null>(null);

  useEffect(() => {
    listResource<Craft>("/crafts", { pageSize: LIST_PAGE_CEILING })
      .then((result) => {
        setCrafts(result.items);
        setCraftCut(listCut(result, "crafts"));
        setCraftListState("loaded");
      })
      .catch(() => {
        setCrafts([]);
        setCraftListState("unavailable");
      });
  }, []);

  /**
   * THIS ARTISAN'S OWN CRAFT IS ALWAYS AN OPTION, wherever it sorts.
   *
   * Editing an artisan whose craft sits past the alphabetical cut drew a REQUIRED dropdown with
   * nothing selected in it, beside a box inviting a new craft name — so the obvious repair for what
   * looked like missing data was to type the craft in again and duplicate it. See `useRecordOffPage`.
   */
  const offPageCraft = useRecordOffPage<Craft>("/crafts", craftId, crafts);
  const craftOptions = useMemo(() => (offPageCraft ? mergeById(crafts, [offPageCraft]) : crafts), [crafts, offPageCraft]);

  /**
   * The craft and the workshop carry into a new artisan; the ARTISAN in the bag never does.
   *
   * This form's whole job is to create a person who is not yet in the bag, so prefilling the last
   * one would be worse than useless — it is the "wrong artisan" hazard with the record itself as the
   * casualty. Their place does not carry either: it belongs to that artisan, not to the sitting, and
   * two artisans documented back to back are routinely from different villages. What genuinely
   * transfers is the craft everyone at this workshop practises, and the workshop.
   */
  const carry = useCarryContext({
    enabled: !initial,
    scopes: [carryScope("craft", craftListState, craftOptions)],
    /*
      A KEY THE SEED ALREADY ANSWERED IS NOT OFFERED, or the banner would name a workshop that is
      nowhere on the form. `applies` is what the banner claims to have filled in, and a claim about
      a box holding somebody else's answer is exactly the invisible prefill this banner exists to
      prevent — see `CarryContextBanner`'s header.
    */
    applies: seed?.workshopId ? ["craft"] : ["craft", "workshop"],
    onApply: (context) => {
      if (context.craftId) setCraftId(context.craftId);
      if (context.workshopId && !workshop.touched) workshop.setWorkshopId(context.workshopId);
    }
  });
  /** "Change": drop the carried craft so the researcher picks from scratch. */
  function clearCarriedContext() {
    carry.change();
    setCraftId("");
  }

  /**
   * Leave this form — to the host's idea of "away", which is not always a navigation.
   *
   * ── THE DEFECT THIS ENDS ──────────────────────────────────────────────────────────────────
   * `router.back()` was the only exit, and it is wrong in a dialog: the dialog is not a route, so
   * back popped the REAL history entry and took the designer out of the half-filled stage they
   * were standing in. Cancel is the most natural way to back out of a modal and it was the one
   * control that lost their place. The save path had already been audited for exactly this hazard
   * (see the `onCreated` branch below); the cancel path had not.
   */
  function leave() {
    if (onCancel) onCancel();
    else router.back();
  }

  /**
   * Finish the exit the HOST'S OWN back control began, after "Discard" has answered for the typing.
   *
   * `useLeaveGuard` does not delay a navigation, it REFUSES one: the interceptor returns true, the
   * back control abandons what it was doing, and this form is handed the question instead. So
   * nothing is left in flight to resume — only the host knows where the arrow was going, and only
   * the host can start it again. `onDiscardAndLeave` is how it says so.
   *
   * Falls back to the ordinary exit when no host supplies it, which is right for both other hosts:
   * on this form's own route `leave()` is `router.back()`, which IS the navigation the arrow
   * wanted, and in `InlineRecordDialog` closing the dialog is the whole of leaving it. Only a host
   * that can be left without being closed — the stage embed — has anything to add. See
   * `InlineRecordHostProps.onDiscardAndLeave`.
   */
  function leaveAfterDiscard() {
    if (onDiscardAndLeave) onDiscardAndLeave();
    else leave();
  }

  function handleBack() {
    // `promptFromCancel`: this is the form's own Cancel, so "Discard" must NOT complete a
    // navigation nobody started — see the flag's declaration.
    if (dirty) {
      setPromptFromCancel(true);
      setBackPromptOpen(true);
    } else leave();
  }

  /**
   * Throw the in-progress entry away and start from a clean form.
   *
   * The workshop and the craft are deliberately left alone: the researcher is still standing in the
   * same workshop documenting the same craft, and re-picking both after every discarded duplicate
   * would be busywork. Clearing the craft would also make the carry-forward banner above lie about
   * a field it no longer fills.
   */
  function discardEntry() {
    setDuplicatePromptOpen(false);
    setConflict(null);
    setError(null);
    setMediaFiles([]);
    setEmail(initial?.email ?? "");
    // The two dates live in React state, so the remount below cannot clear them — the same reason
    // `email` is on this list. Back to what the record said, which for a new artisan is blank.
    setDateOfBirth(initial?.dateOfBirth ? String(initial.dateOfBirth).slice(0, 10) : "");
    setCraftStartDate(initial?.craftStartDate ? String(initial.craftStartDate).slice(0, 10) : "");
    resetDirty();
    setFormKey((key) => key + 1);
    if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // Read the form synchronously: React nulls event.currentTarget across the await below.
    const form = new FormData(event.currentTarget);
    setError(null);
    setConflict(null);
    // Ask about a duplicate BEFORE the late-submission prompt: there is no point weighing up a late
    // save that is about to be abandoned anyway.
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
    // A workshop that has already ended makes this a late submission needing admin approval — say so
    // before anything is written. Resolves true immediately when there is nothing to warn about.
    if (!(await workshop.confirmSubmission())) return;
    setSaving(true);
    try {
      const exifItems = await collectExifMetadata(mediaFiles);
      const exifRemark = exifMetadataToRemark(exifItems);
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      // Everything LocationFields renders, including the state and pincode that used to be merged
      // in here by hand — `locationFromForm` reads them now, so the five other forms that share it
      // stopped throwing the two answers away. Also goes onto the media batch below: same place.
      const location = locationFromForm(form);
      // Android parity: an artisan needs either an existing craft or a new craft name.
      const craftId = textValue(form, "craftId");
      const newCraftName = textValue(form, "newCraftName");
      if (!craftId && !newCraftName) {
        setError("Select an existing craft or enter a new craft name.");
        setSaving(false);
        return;
      }
      // The Yes/No dropdown mirrors its option value into FormData; anything other than an explicit
      // "No" means the artisan holds a card, which matches the API's own default of Yes.
      const pehchanAvailable = textValue(form, "pehchanCardAvailable") !== PEHCHAN_NO;
      const pehchanNumber = textValue(form, "pehchanCardNumber");
      // A masked card number means the editor was never shown the real one and left it alone. Unlike
      // the Aadhaar mask the API does NOT recognise this one — `validate_pehchan` happily normalises
      // "XXXX XXXX 3456" to "XXXXXXXX3456" and stores it over the real card — so the key is dropped
      // from the payload instead, which a PATCH reads as "not sent, not changed".
      const pehchanUnchanged = pehchanAvailable && isMaskedIdentityNumber(pehchanNumber);
      const payload = {
        name: requiredText(form, "name"),
        localName: textValue(form, "localName"),
        gender: textValue(form, "gender"),
        // `null` and not `undefined` when blank: on a PATCH an omitted key means "leave it alone",
        // so clearing a date somebody entered by mistake would silently do nothing.
        dateOfBirth: textValue(form, "dateOfBirth") || null,
        // Both dates arrive through `DateField`'s hidden input, so they read exactly as the native
        // date inputs they replaced did. `null` and not `undefined` when blank, for the reason
        // above: an omitted key on a PATCH means "leave it alone", so clearing a joining date
        // somebody entered by mistake would silently do nothing — and `craftStartDate` is in the
        // API's `_CLEARABLE_COLUMNS` precisely so that retraction works.
        craftStartDate: textValue(form, "craftStartDate") || null,
        // THE STATED NUMBER, STILL SENT. It is the second of the three answers the server reads (the
        // derived value from `craftStartDate` first, this column next, the legacy `extraMetadata`
        // spellings last), so sending it is what keeps a value that is currently right from being
        // blanked by a form that has learnt about dates. See the box itself for why it is typeable.
        experienceYears: textValue(form, "experienceYears")
          ? Number(textValue(form, "experienceYears"))
          : null,
        phone: textValue(form, "phone"),
        email: textValue(form, "email"),
        place: requiredText(form, "place"),
        address: textValue(form, "address"),
        // `appendStoredParagraph` and NOT `appendRemarksWithExif`: the notes box is a rich-text
        // editor now, so this column may hold a JSON document. Concatenating the EXIF summary onto
        // the end of a JSON string produces a value that is neither valid JSON nor readable prose —
        // the editor would show raw braces followed by the summary, and so would every CSV. The
        // helper appends INTO the document when there is one and behaves byte-for-byte like
        // `appendRemarksWithExif` when there is not.
        notes: appendStoredParagraph(textValue(form, "notes") as string | null, exifRemark, "paragraph"),
        // Identity. The Aadhaar mirror input carries the bare digits (the visible box only groups
        // them for reading), or the mask verbatim when the editor was never shown the real number —
        // which the API recognises and drops, leaving the stored value alone. The Pehchan mask has
        // no such server-side guard, so it is omitted above instead. The card number IS sent, as an
        // explicit null, when the artisan holds no card, so an edit that flips the answer to No
        // clears the stored number instead of orphaning it: `aadhaarNumber` and `pehchanCardNumber`
        // are both clearable server-side.
        aadhaarNumber: textValue(form, "aadhaarNumber"),
        pehchanCardAvailable: pehchanAvailable,
        ...(pehchanUnchanged ? {} : { pehchanCardNumber: pehchanAvailable ? pehchanNumber : null }),
        dos: requiredText(form, "dos"),
        donts: requiredText(form, "donts"),
        craftId,
        craftName: craftId ? null : newCraftName,
        workshopId: workshop.workshopId || null,
        // Below professor no status control is rendered: create submits PENDING, edit resubmits the
        // current status (the backend drops unauthorized changes either way).
        status: requiredText(form, "status") || initial?.status || "PENDING",
        recordedAt,
        recordedTimezone,
        location,
        // extraMetadata stays programmatic (EXIF etc.) — the raw JSON textarea was removed.
        extraMetadata: exifItems.length ? { mediaExif: exifItems } : {}
      };
      // With no connection this queues to the offline outbox instead of failing at the Save button;
      // the media goes with it, because the artisan will have gone home by the time signal returns.
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
          /*
            THE PAGE'S ANSWER IS UNREACHABLE FROM A DIALOG, so the host is told instead.

            `OutboxBanner` is mounted at the top of the protected layout — outside the portal,
            underneath `FieldDialog`'s overlay, on a body whose scroll `FieldDialog` has locked. So
            the banner is invisible and the scroll below is a no-op. `onCreated` is not called
            either (there is no record and no server id), which meant the button flipped back from
            "Saving…" to "Save artisan" and nothing else on screen changed. That is
            indistinguishable from a save that FAILED, and the designer's next move is to press it
            again — three copies of one artisan in the outbox, all of which sync in as duplicates.
          */
          onQueued();
          return;
        }
        // No per-form "queued" banner ON THE PAGE HOST: OutboxBanner at the top of the page already
        // names the entry and is the one place that says where it lives. Scroll so it is the next
        // thing seen. See the dialog branch above for why that reasoning does not travel.
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        return;
      }
      const saved = outcome.saved;
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
            `${failed.length} of ${mediaFiles.length} file(s) failed to upload: ${failed.map((f) => f.name).join(", ")}. ` +
              "The artisan record was saved; re-open it to retry those files."
          );
          setSaving(false);
          /*
            ── THE RECORD IS REPORTED FIRST, THE UPLOAD FAILURE SECOND ────────────────────────
            This branch used to `return` here, and the sentence it had just written says why that
            was wrong: the artisan IS in the repository. Only the photographs are missing. But the
            host was never told, so the stage row that opened this form stayed unlinked over a
            record that exists — and an unlinked REF is not something the designer can see and
            repair later: the stage 422s on submit, hours afterwards, naming a required reference
            for a person they remember creating. A missing photograph is recoverable by re-opening
            the record, which is what the message above tells them to do; a link nobody made is not.

            THE ERROR IS SET BEFORE THE HANDOFF AND NOT INSTEAD OF IT. On the form's own page there
            is no host, nothing unmounts, and the banner is read exactly as it always was. In the
            dialog the host closes over it — that is the trade, and it is the same one the queued
            branch above already makes.
          */
          if (onCreated) onCreated(saved);
          return;
        }
      }
      resetDirty();
      if (onCreated) {
        // Hosted in a dialog: the caller owns what happens next. Scrolling the page behind the
        // dialog, or routing away from the stage the designer is standing in, would both be wrong.
        onCreated(saved);
      } else if (initial) {
        router.push("/artisans");
        router.refresh();
      } else {
        setSavedRecord(saved);
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
      }
    } catch (err) {
      // A duplicate Aadhaar/Pehchan number is the deduplication working, not a breakage: show the
      // server's sentence and a way to reach the artisan who already holds the number.
      const duplicate = identityConflict(err);
      if (duplicate) {
        setConflict(duplicate);
        // Same dialog as the pre-flight catch, so a duplicate reads identically whether it was found
        // before the request or by the unique index behind it.
        setDuplicatePromptOpen(true);
        // The panel renders at the top of a long form while the researcher is at the Save button:
        // without this the save simply appears to do nothing.
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
            Saved &ldquo;{savedRecord.name}&rdquo;. Continue documenting with the same context, or add another artisan.
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            <button type="button" className="field-button-secondary" onClick={() => { setSavedRecord(null); setMediaFiles([]); setEmail(""); }}>
              Add another artisan
            </button>
            <button type="button" className="field-button-secondary" onClick={() => { router.push("/artisans"); router.refresh(); }}>
              Back to artisans
            </button>
          </div>
        </div>
        {/*
          THE CODE FOR THE RECORD THAT WAS JUST MADE, at the one moment somebody wants it.

          The card is otherwise only on `/artisans/{id}/edit`, so a researcher who wanted to print a
          tag for the artisan sitting in front of them had to save, find the record in a list and
          re-open it — three navigations to reach a symbol that is a pure function of the id they
          were just handed. Nothing is fetched and nothing is stored: `RecordCodeCard` draws it from
          the type and the id (see its header).

          THIS PANEL IS THE HOST-FREE PATH BY CONSTRUCTION. `savedRecord` is only ever set in the
          `else` of `if (onCreated) … else if (initial) …`, so a dialog host — which closes on
          create and never renders this — cannot reach it.
        */}
        <RecordCodeCard recordType="artisan" id={savedRecord.id} title={savedRecord.name} />
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

  /**
   * The artisan named by the conflict panel, lifted into a const so the branch below can close over
   * it. `conflict?.existingArtisan` is a property access, and a property narrowed in a test is not
   * narrowed again inside the click handler that reads it.
   */
  const conflictArtisan = conflict?.existingArtisan ?? null;

  return (
    <>
      <form
        key={formKey}
        ref={formRef}
        onSubmit={submit}
        onInput={markDirty}
        onKeyDown={handleFormEnter}
        className="panel grid gap-4 p-4"
      >
        {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
        <CarryContextBanner offer={carry.applied} onChange={clearCarriedContext} />
        {conflict ? (
          <div role="alert" className="rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm text-amber-800">
            <p className="font-medium">{conflict.message}</p>
            {/*
              ── THE PANEL'S OWN WAY OUT IS HOST-AWARE, LIKE THE DIALOG'S ──────────────────────
              `DuplicateArtisanDialog` is the one-time question and it was taught to hand the
              artisan back (see `onOpenExisting` below); THIS panel is the reminder that stays on
              screen after the question has been dismissed, and it still ended in a `<Link>` to
              /artisans/{id}/edit. So the two controls that say the same thing disagreed about what
              acting on it costs: dismiss the dialog, read the amber panel, follow its link, and the
              half-filled 22-stage record the designer was standing in is gone — from the surface
              that exists precisely so they never have to leave it.

              A button and not an intercepted link, for the reason `AadhaarField` gives beside its
              own copy of this control: with a host there is no navigation to intercept, and an
              anchor is a middle-click away from leaving anyway. Only the id and the name cross —
              `maskedValue` sits on the same payload and must never reach a stage entry, see
              {@link UseExistingArtisan}.
            */}
            {conflictArtisan ? (
              onUseExisting ? (
                <button
                  type="button"
                  className="mt-1 inline-block font-medium underline"
                  onClick={() => {
                    // Same reasoning as the dialog's branch: taking the other record discards this
                    // entry either way, so do not make them answer a second prompt on the way.
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
        <div className="grid gap-3 md:grid-cols-2">
          {/* Android parity (ArtisanForm): the workshop opens the form, because it is the context
              every other answer belongs to — not merely the first dropdown. */}
          <WorkshopSelect state={workshop} onDirty={markDirty} saving={saving} />
          <Field label="Name" required>
            {/* Name, new craft name and place are title-cased by the API on write, so the box says
                what will actually be stored (Android parity — see components/forms/TitleCasedInput). */}
            <TitleCasedInput name="name" required defaultValue={initial?.name ?? ""} />
          </Field>
          <Field label="Local name">
            <TextInput name="localName" defaultValue={initial?.localName ?? ""} />
          </Field>
          <Field label="Craft" required>
            {/* `searchable`: crafts are records and this list is capped (see the notice below it).
                Nine crafts today is one either side of the option-count threshold, so without this
                the same required field would have a filter box on one deployment and not on the
                next — see `SearchableSelectProps.searchable` for the rule. */}
            <Select
              name="craftId"
              searchable
              value={craftId}
              onChange={(event) => {
                setCraftId(event.target.value);
                // An explicit pick replaces the remembered craft and retires the banner: from here
                // on what is on screen is the researcher's own choice, not a suggestion.
                const craft = craftOptions.find((candidate) => candidate.id === event.target.value);
                if (craft) carry.remember({ craftId: craft.id, craftName: craft.name }, { explicit: true });
                markDirty();
              }}
            >
              <option value="">Select existing craft</option>
              {craftOptions.map((craft) => (
                <option value={craft.id} key={craft.id}>
                  {craft.name}
                </option>
              ))}
            </Select>
            <CappedListNotice cuts={[craftCut]} />
          </Field>
          <Field label="Or new craft name">
            <TitleCasedInput name="newCraftName" placeholder="Used when no existing craft is selected" />
          </Field>
          <Field label="Place" required>
            <TitleCasedInput name="place" required defaultValue={initial?.place ?? ""} />
          </Field>
          <Field label="Gender">
            {/* NO `searchable` here, on Status, or on the Pehchan Yes/No — deliberately, and it is
                the same decision every fixed vocabulary in this app makes. Four options are read at
                a glance, a filter box over them is one more tab stop and a "No matches" state, and
                the plain list keeps the native type-ahead the e2e suite pins: focus Gender, press
                "f", get Female. `SEARCH_THRESHOLD` reaches this on its own; the comment is here so
                the next reader sees the asymmetry with Craft above was chosen. */}
            <Select name="gender" defaultValue={initial?.gender?.trim() ? initial.gender : "Male"} onChange={markDirty}>
              {genderOptions.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
          {/* ── THE TWO FACTS THE DESIGN WORKSHOP ASKS OF EVERY ARTISAN ────────────────────
              The workshop's participant table declares `age` and `experienceYears` as fields the
              reference picker fills in — their help text promises the designer exactly that — and
              until these inputs existed nothing on this page could answer either. So an artisan
              IMPORTED into a workshop arrived with both boxes blank and an artisan ADDED from
              inside one had nowhere to record them, and the designer typed them in from a printout
              beside a row that already named this record.

              A DATE AND NOT A NUMBER, TWICE OVER, and that is the shape of both answers. The
              workshop asks for an AGE and for YEARS OF EXPERIENCE; storing either would be wrong
              within a year with nothing anywhere to say so, so what is stored is the DATE and the
              number is derived from it every time it is read — on this page, in the workshop's
              participant table, and in the report. That is also why each box carries the sentence
              it carries: somebody filling one in is answering a question they were not asked, and
              needs to know why.

              THERE IS NO AGE BOX ON THIS FORM AND THERE NEVER WAS ONE, and the absence is the
              feature rather than an omission. A typed age is the defect `dateOfBirth` exists to
              prevent, so the age below is a READOUT — text, with no `name`, submitted nowhere and
              stored nowhere. The experience box that remains is a different thing: it is the STATED
              number, second in the server's precedence, and it is still typeable for the reason
              written out on it. */}
          {/* Not `Field`: see `derivedFieldsId` above for why a `<DateField>` may not be wrapped in
              a `<label>`, and why the hint sits outside it. */}
          <div className="grid min-w-0 gap-1">
            <label className="field-label" htmlFor={dobId}>
              Date of birth
            </label>
            {/*
              `DateField` and NOT `<TextInput type="date">`, and this was the last native date input
              left in the app. A native date input formats itself from the BROWSER's locale rather
              than this app's, so a birthday recorded as 02/03/1971 is February to a researcher on
              en-IN and March to the administrator checking it on en-US — and since both readings
              are valid dates, nothing anywhere reports a problem. `DesignerProfileForm` and
              `FieldInput` both carry the same argument in full, and there is no field season in
              which that error is recoverable, because the two readings are equally plausible dates.

              The wire format is unchanged: this renders a hidden input under the same name carrying
              the same `yyyy-mm-dd`, so `submit` below reads it exactly as it did.

              `onChange` is load-bearing rather than decoration, and it does two jobs. The form
              raises its dirty flag from `onInput`, and picking a day out of the calendar grid sets
              React state without dispatching an input event — the same trap the themed dropdowns on
              this form document — so without this, choosing a birthday by calendar and then
              navigating away would be discarded with no unsaved-changes prompt at all. It is also
              what moves the age readout below, in the same frame.

              The typed `dd/mm/yyyy` box is the fast path for a birthday and the calendar is the slow
              one: the grid opens on the current month and a 1971 date is a long way back from there.
              That is why this control keeps a text input rather than being a button that only opens
              a picker.
            */}
            <DateField
              id={dobId}
              name="dateOfBirth"
              value={dateOfBirth}
              onChange={(iso) => {
                setDateOfBirth(iso);
                markDirty();
              }}
              max={todayIso}
              describedBy={dobHintId}
            />
            <p id={dobHintId} className="text-xs leading-5 text-ink-muted">
              {derivedAge === null
                ? "The workshop's participant table shows an age, worked out from this."
                : `Age ${derivedAge}. The workshop's participant table shows an age, worked out from this.`}
            </p>
          </div>
          {/* THE FEEDER THE EXPERIENCE BELOW IS DERIVED FROM, at the owner's request that experience
              become a derived field with a date of joining the craft behind it.
              `Artisan.craftStartDate` is NULL on every row written before 2026-08-23 and the
              migration deliberately refuses to guess one, so an old record showing this box empty is
              the expected state — not a gap for somebody to fill in from a printout. */}
          <div className="grid min-w-0 gap-1">
            <label className="field-label" htmlFor={craftStartId}>
              Practising since
            </label>
            <DateField
              id={craftStartId}
              name="craftStartDate"
              value={craftStartDate}
              onChange={(iso) => {
                setCraftStartDate(iso);
                markDirty();
              }}
              max={todayIso}
              describedBy={craftStartHintId}
            />
            <p id={craftStartHintId} className="text-xs leading-5 text-ink-muted">
              {derivedExperience === null
                ? "The date the artisan took up the craft. The years of experience are worked out from it."
                : `${derivedExperience} years of experience, worked out from this — the figure the workshop and the report use.`}
            </p>
          </div>
          {/* Not `Field`, so the sentence underneath can be an `aria-describedby` paragraph instead
              of two more clauses folded into the box's accessible name. */}
          <div className="grid min-w-0 gap-1">
            <label className="field-label" htmlFor={experienceId}>
              Experience (years)
            </label>
            {/*
              ── WHY A TYPED NUMBER SURVIVES ON A FORM WHOSE EXPERIENCE IS NOW DERIVED ──────────
              The date above outranks this box everywhere the value is read, so the obvious tidy-up
              is to delete it, or to disable it whenever a date is present. Both would destroy data
              that is currently right, which is the one thing this change may not do.

              An artisan who says "about thirty years" and cannot name a year is the ordinary case
              rather than the exception — `Artisan.experienceYears`' own column comment is an
              argument for exactly that, and it is kept — and every row written before the join-date
              column existed answers this question through this number, or through a legacy "30+"
              sitting behind it. `participant.experienceYears` is a TABLE_COLUMN in a submitted
              report, so a form that refused to hold their answer would print a blank in the
              participant table for the oldest and best-documented artisans in the repository.

              DISABLING IT WHILE A DATE IS SET WOULD BE WORSE THAN LEAVING IT ALONE, and not for a
              style reason: a disabled input is omitted from FormData, `submit` reads the absence as
              an explicit null, and the API clears the column. The stated number would be gone — so
              clearing the joining date a week later, on a record whose number nobody re-typed, would
              leave that artisan with no experience at all. Kept enabled, both answers stand on the
              row and the precedence decides between them on every read.
            */}
            <TextInput
              id={experienceId}
              name="experienceYears"
              type="number"
              inputMode="numeric"
              /* 0..90 mirrors the stage registry's own bounds for `participant.experienceYears`.
                 A wider range here would accept a number the workshop then refuses on a row it
                 filled in from this very record. */
              min={0}
              max={90}
              step={1}
              defaultValue={initial?.experienceYears ?? ""}
              onChange={markDirty}
              aria-describedby={experienceHintId}
            />
            <p id={experienceHintId} className="text-xs leading-5 text-ink-muted">
              {derivedExperience === null
                ? "Used when there is no “practising since” date: a stated number, which does not change as the years pass."
                : `The date above answers this — ${derivedExperience} years is what the workshop and the report print. A number here stays on the record and is read only while that date is empty.`}
            </p>
          </div>
          {/* FieldBlock, not Field: PhoneField contains a themed dropdown, and `Field` is a
              `<label>` — so the visible word "Phone" bound itself to the dial-code trigger (the
              first labelable descendant) rather than to the number box, and clicking the label
              opened a 246-entry country list. The same swap was already made on the designer
              profile form for the same control. */}
          <FieldBlock label="Phone">
            <PhoneField name="phone" defaultValue={initial?.phone} onValueChange={markDirty} />
          </FieldBlock>
          <Field label="Email">
            <TextInput
              name="email"
              type="email"
              pattern="[^\s@]+@[^\s@]+\.[^\s@]+"
              title="name@example.com"
              value={email}
              aria-invalid={!!emailError}
              // Without this the box announced itself as invalid and kept the reason to itself —
              // the sentence explaining what is wrong with the address was red text and nothing more.
              aria-describedby={emailError ? emailErrorId : undefined}
              onChange={(event) => setEmail(event.target.value)}
            />
            {emailError ? (
              <p id={emailErrorId} role="alert" className="text-xs text-error-600">
                {emailError}
              </p>
            ) : null}
          </Field>
          {/*
            DICTATION BUT NOT RICH TEXT, and the split is the whole point of there being two
            controls. An address is three lines, so a researcher standing in a courtyard genuinely
            wants to speak it rather than thumb it in — but a bold word or a bulleted list in a
            postal address is meaningless, and a formatting toolbar here would be an invitation to
            store a document in a column that four exports print as a delivery address.
          */}
          <DictatedTextArea
            name="address"
            label="Address"
            defaultValue={initial?.address ?? ""}
            onDirty={markDirty}
          />
          {/*
            NOTES IS THE ONE LARGE NARRATIVE BOX ON THIS FORM, so it is the one that gets the editor.

            IT REPLACES `MultiNoteField`, whose several textareas were joined with a blank line into
            the same `Artisan.notes` column. `join="paragraph"` is what keeps that contract: an
            unformatted document is written back blank-line separated, so Android's `MultiNoteInput`
            — which SPLITS on blank lines to rebuild its rows — still reconstructs exactly the notes
            that were written here. Drop that argument and four notes silently become one the next
            time the record is opened on a handset.

            The EXIF remark that `submit` appends to this field goes through `appendStoredParagraph`
            rather than `appendRemarksWithExif` for the same reason: concatenating plain text onto a
            stored document would produce a value that is neither.
          */}
          <RichTextField
            name="notes"
            label="Notes"
            defaultValue={initial?.notes ?? ""}
            join="paragraph"
            className="md:col-span-2"
            onDirty={markDirty}
          />
          {/* Android parity (ArtisanForm): the three identity answers sit after the contact and
              notes fields and before Do's/Don'ts. Grouping them makes the dependency between
              "holds a card" and "card number" obvious at a glance. */}
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
                Government identifiers, kept so the same artisan documented at two workshops resolves
                to one record. Stored securely and masked on every shared or exported view.
                {aadhaarRequired
                  ? ""
                  : " This artisan was recorded before an Aadhaar number was required, so the record still saves without one — add it only if the artisan is willing."}
              </p>
            </div>
            <AadhaarField
              defaultValue={initial?.aadhaarNumber}
              excludeArtisanId={initial?.id ?? null}
              required={aadhaarRequired}
              // The researcher is sitting with the artisan and the card: this is the one form where
              // photographing it instead of typing twelve digits is the right offer. Android's
              // artisan form carries the same control under the same box.
              offerCardCapture
              // The field's own duplicate warning ends in a link to the other artisan's edit page,
              // which is a trapdoor out of a hosted form. Handed the same callback the duplicate
              // dialog uses, it offers to USE that artisan instead. See `AadhaarField`'s prop.
              onUseExisting={onUseExisting}
              onValueChange={markDirty}
            />
            <PehchanFields
              initialAvailable={initial?.pehchanCardAvailable ?? true}
              initialNumber={initial?.pehchanCardNumber}
              onDirty={markDirty}
            />
          </div>
          <DosDontsField
            name="dos"
            label="Do's (positive prompt)"
            helper="Lessons from years at the craft — the things the artisan has learnt to do. Press Enter for each new point."
            defaultValue={initial?.dos}
          />
          <DosDontsField
            name="donts"
            label="Don'ts (negative prompt)"
            helper="Lessons from years at the craft — the things the artisan has learnt not to do / to avoid. Press Enter for each new point."
            defaultValue={initial?.donts}
          />
          <StatusField canSetStatus={canSetStatus} initialStatus={initial?.status} onDirty={markDirty} />
        </div>
        {initial ? <ExistingMedia linkedRecordType="artisan" linkedRecordId={initial.id} /> : null}
        <MediaCaptureField
          files={mediaFiles}
          onFilesChange={(files) => {
            setMediaFiles(files);
            markDirty();
          }}
          title="Artisan media"
          description="Attach or capture artisan images, audio introductions, videos, and documents. Image EXIF is retained and summarized in notes."
        />
        {/*
          `statedPlace` is the free-text box the researchers used while there was no district column
          — "Bagru, Jaipur, Rajasthan", "Rudraprayag, Dehradun" — and it is passed READ ONLY, so the
          card can tell a researcher that this record's Kharagpur coordinates disagree with the
          Rajasthan place they typed. Nothing is parsed out of it and written back.
        */}
        <LocationFields
          initial={initialLocation}
          onDirty={markDirty}
          subjectLabel="the artisan"
          statedPlace={initial?.place}
        />
        {uploadProgress ? <UploadProgress progress={uploadProgress} /> : null}
        {/*
          THE HOST'S OWN QUESTIONS, AT THE BOTTOM OF THE SAME LIST OF FIELDS — see
          `InlineRecordHostProps.footerFields`. Inside the `<form>` and above the buttons, because a
          design-workshop stage embedding this page asks a few things the artisan record does not
          hold, and they have to read as the last fields of one form rather than as a second panel
          under a form that has already ended. The separator is the only styling: nothing above it
          is touched, and with no host there is no element at all.
        */}
        {footerFields ? <div className="grid gap-3 border-t border-line-200 pt-4">{footerFields}</div> : null}
        <div className="flex justify-end gap-2">
          <button type="button" className="field-button-secondary" onClick={handleBack}>
            Cancel
          </button>
          <button className="field-button" disabled={saving || checkingDuplicate}>
            {checkingDuplicate ? "Checking..." : saving ? "Saving..." : initial ? "Update artisan" : "Save artisan"}
          </button>
        </div>
      </form>
      <DuplicateArtisanDialog
        open={duplicatePromptOpen}
        artisan={conflict?.existingArtisan}
        message={conflict?.message}
        maskedValue={conflict?.maskedValue}
        onOpenExisting={() => {
          setDuplicatePromptOpen(false);
          // Leaving for the other record discards this one either way, so drop the guard rather than
          // making the researcher answer a second "unsaved changes" prompt on the way out.
          resetDirty();
          if (!conflict?.existingArtisan) return;
          /*
            ── HOSTED IN A DIALOG, THE ANSWER IS THE ARTISAN, NOT A ROUTE ──────────────────────
            Inside the inline record dialog a duplicate is the ORDINARY outcome, not the exception:
            the designer pressed "Create a new artisan" precisely because the picker's search did
            not show the person standing in front of them, and the deduplication key may still
            match. `router.push` threw them onto /artisans/{id}/edit with the stage gone — so
            acting on the one thing this prompt exists to surface, "she is already in the
            repository", cost them their place. Handed back instead, the picker links her, asks the
            server to describe her, and hydrates the row exactly as an ordinary pick would.

            NOTHING FROM THIS PAYLOAD IS WRITTEN ONTO THE ROW. It carries `maskedValue` — a masked
            Aadhaar or Pehchan string — and that must never reach a stage entry. Only the id and
            the name cross, and the name only as the term the picker searches with; every value
            that lands comes back from the server. See `UseExistingArtisan`.

            The comment above still governs the page host below it: there, leaving really is a
            navigation and the guard really would be a second prompt on the way out.
          */
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
          /*
            NEITHER BRANCH IS `router.back()`: the prompt is as load-bearing in a dialog as on a
            page — closing the dialog still throws the typing away — but what "discard" DOES
            afterwards belongs to the host.

            WHICH host act, though, depends on which control asked. Cancel means "empty this form,
            I am staying", and in the stage embed that is exactly what `leave()` does. The back
            arrow means "take me off this screen", and answering it with `leave()` alone is the
            defect `promptFromCancel` exists for: the work was discarded and the designer did not
            go anywhere.
          */
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
