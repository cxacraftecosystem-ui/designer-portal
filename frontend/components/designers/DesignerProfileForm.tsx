"use client";

/**
 * The designer profile editor — all twenty-one columns, one save, one PUT.
 *
 * WHY THIS FORM SENDS EVERY KEY ON EVERY SAVE. `PUT /designers/{…}/profile` applies its body with
 * `exclude_unset`, so an ABSENT key leaves the stored value alone and a key present and NULL clears
 * it. A form that simply omitted its empty boxes would watch a deleted department reappear on the
 * next load, every time, with no error and nothing on screen to blame — and there would be no way
 * to empty a field at all. This screen renders every column, so sending every column is correct and
 * `fullDesignerProfileBody` is the encoder that guarantees it. A future PARTIAL editor must build a
 * narrower body of its own; reusing that encoder would erase whatever it does not render.
 *
 * WHY THE ADDRESS IS A STATE DROPDOWN AND NOT A TEXT BOX. The state is the one part of an address
 * the whole dataset is grouped by, and `LocationFields` already proved what happens when the list
 * comes only from `GET /reference/address`: with no signal the list was empty, a required closed
 * list had no members, and the record could not be saved at all. So the same discipline applies
 * here — the 36 bundled names (`OFFLINE_STATES`, read out of the postal-zone table rather than
 * copied) answer the question with no network, and the served list takes over the moment it lands.
 * The full `LocationFields` card is deliberately NOT reused: it captures a device fix, a subject
 * coordinate, a district and a village against a Location row, and a designer's correspondence
 * address is none of those things — it is four plain columns on `DesignerProfile`. Its two exported
 * validators are reused instead, so a pincode is judged here by exactly the rule that judges one on
 * the artisan form.
 *
 * WHY THE PHOTOGRAPH IS A MEDIA ID AND THE UPLOAD HAPPENS BEFORE THE SAVE. The column stores an id
 * that the report resolves through `GET /media/{id}`; a URL would expire. `MediaCaptureField`
 * pre-uploads eagerly the moment a file is attached, so by the time Save runs `uploadMediaBatch`
 * usually only has to LINK the finished object — and because that call resolves rather than throws
 * on a partly-failed batch, the failures it reports are named on screen instead of being read as
 * success. A FAILURE ALSO LEAVES THE FILE IN THE CAPTURE CARD: the save used to clear both cards
 * unconditionally, in the same statement that wrote "so the one already on file was kept" into the
 * notice, which discarded a photograph that existed nowhere but in this browser. See `uploadOne`,
 * whose `stranded` files are what the three cards are re-seeded with.
 *
 * AND THE NOTICE SAYS SO, WHICH IS THE HALF THAT WAS MISSING. Keeping the bytes silently was still
 * wrong: the designer read "Profile saved", got an "Unsaved changes" chip and a leave prompt on it,
 * and nothing on screen said why either had happened or what to do. So the trouble sentence names
 * the card, and one sentence added beside it names the retry — which on THIS form is a real one and
 * not a hopeful one: `save` is a single idempotent PUT keyed on `profile.userId`, so a second Save
 * re-sends the file that failed and creates nothing. (The crafts and workshops pages cannot say that
 * — a second Save there POSTs a second record — which is why their wording differs.)
 */

import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { IdCard, Trash2 } from "lucide-react";

import { StoredMediaImage } from "@/components/designers/StoredMediaImage";
import {
  DESIGNER_PROFILE_COPY_NOTICE,
  DESIGNER_PROFILE_GROUPS,
  DESIGNER_PROFILE_HELP,
  DESIGNER_PROFILE_LABELS,
  type DesignerProfileGroupKey
} from "@/components/designers/profileCopy";
import { Field, Select, TextArea, TextInput } from "@/components/FormControls";
import { DateField } from "@/components/forms/DateTimeField";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { DocumentPreview } from "@/components/media/DocumentPreview";
import { PhoneField } from "@/components/forms/PhoneField";
import {
  OFFLINE_STATES,
  loadAddressReference,
  pincodeValidationError,
  postalZoneMismatch
} from "@/components/forms/LocationFields";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { handleFormEnter } from "@/lib/formNav";
import { useUnsavedChanges } from "@/lib/forms";
import { uploadMediaBatch } from "@/lib/media";
import {
  DESIGNER_PROFILE_MEDIA_RECORD_TYPE,
  fullDesignerProfileBody,
  type DesignerProfile,
  type DesignerProfileField,
  type DesignerProfileUpdateBody
} from "@/lib/designers";
import type { AddressReference } from "@/lib/types";

/** The `MAX_LENGTH` of each text column, from `DesignerProfileUpdate` in the backend schema. */
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

export function DesignerProfileForm({
  profile,
  save,
  onSaved,
  /**
   * Whose profile this is, for the copy: "your" on the account's own page, the designer's name on
   * an admin's. It is only ever wording — the authority to be here was settled by the caller and is
   * settled again by the server on every PUT.
   */
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

  // Controlled because two advisory messages read them, not because the form needs them: the state
  // is a themed dropdown (which fires no native input event, so it marks the form dirty by hand)
  // and the pincode is judged against it while it is being typed.
  const [stateName, setStateName] = useState(profile.state ?? "");
  const [pincode, setPincode] = useState((profile.pincode ?? "").replace(/\D/g, "").slice(0, 6));
  const [reference, setReference] = useState<AddressReference | null>(null);

  // The three single-slot media columns. `*Id` is what is stored; `*Files` is what has been attached
  // in this session and not yet uploaded-and-linked. The CV joined them on 2026-08-25 and takes the
  // identical shape on purpose — it is a one-file column resolved through `GET /media/{id}` exactly
  // as the other two are, and the only thing that differs is what it is DRAWN as (a document
  // preview rather than an `<img>`) and what it will ACCEPT (documents as well as images).
  const [photoId, setPhotoId] = useState(profile.photoMediaId);
  const [signatureId, setSignatureId] = useState(profile.signatureMediaId);
  const [cvId, setCvId] = useState(profile.cvMediaId);
  const [photoFiles, setPhotoFiles] = useState<File[]>([]);
  const [signatureFiles, setSignatureFiles] = useState<File[]>([]);
  const [cvFiles, setCvFiles] = useState<File[]>([]);

  const formRef = useRef<HTMLFormElement | null>(null);

  // Hands the prompt to the round back control in the page header, which is the page's ONE back
  // control. No second back button is added anywhere in this component.
  useLeaveGuard(dirty, () => setBackPromptOpen(true));

  useEffect(() => {
    let live = true;
    loadAddressReference()
      .then((payload) => {
        if (live) setReference(payload);
      })
      .catch(() => {
        // Offline, or the endpoint is unhappy. Swallowed on purpose: the dropdown falls back to the
        // bundled 36 names and stays answerable, and nothing about a failed reference fetch may
        // cost somebody their profile.
      });
    return () => {
      live = false;
    };
  }, []);

  /**
   * The served list when it has arrived, the bundled one until then, with the profile's own value
   * kept at the front if it is in neither — otherwise an edit form would show "Select state" over a
   * state the record really holds, which reads as "not answered" and invites answering it again.
   */
  const stateOptions = useMemo(() => {
    const served = reference?.statesAndUnionTerritories ?? OFFLINE_STATES;
    return stateName && !served.includes(stateName) ? [stateName, ...served] : served;
  }, [reference, stateName]);

  const pincodeProblem = pincodeValidationError(pincode);
  // Advisory, never blocking — it is a zone check and not a lookup of the real code, so it can only
  // ever prove a contradiction, and a wrong guess must not stand between somebody and their own
  // saved profile.
  const zoneProblem = pincodeProblem ? null : postalZoneMismatch(stateName, pincode);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // React nulls `event.currentTarget` across an await, so the FormData is built before any async
    // work — not after the first `await`, where it reads as null and every field arrives empty.
    const form = new FormData(event.currentTarget);

    setSaving(true);
    setError(null);
    setNotice(null);
    const troubles: string[] = [];

    try {
      // Upload first, then save: the profile column has to carry an id, and an id that does not
      // exist yet cannot be written. A failure here leaves the OLD id in place rather than nulling
      // the column — losing the photograph that is already on file because a new one would not
      // upload is a strictly worse outcome than the upload simply not happening.
      let nextPhotoId = photoId;
      let nextSignatureId = signatureId;
      let nextCvId = cvId;

      // The files that did NOT go up, kept so the save path below can put them back in the capture
      // card instead of clearing it. Empty when there was nothing to upload, which is the ordinary
      // case and is exactly what the old unconditional clear did.
      let strandedPhotos: File[] = [];
      let strandedSignatures: File[] = [];
      let strandedCvs: File[] = [];

      if (photoFiles.length) {
        const attempt = await uploadOne(photoFiles, profile.userId, "Designer photograph", troubles);
        if (attempt.mediaId) nextPhotoId = attempt.mediaId;
        strandedPhotos = attempt.stranded;
      }
      if (signatureFiles.length) {
        const attempt = await uploadOne(signatureFiles, profile.userId, "Designer signature", troubles);
        if (attempt.mediaId) nextSignatureId = attempt.mediaId;
        strandedSignatures = attempt.stranded;
      }
      if (cvFiles.length) {
        const attempt = await uploadOne(cvFiles, profile.userId, "Designer CV", troubles);
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
        biography: text(form, "biography"),
        // PhoneField submits the combined "+91 9876543210" through its own zero-size mirror under
        // this name, so it is read from the FormData like any other box.
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
        empanelmentDate: text(form, "empanelmentDate")
      });

      const saved = await save(body);
      /*
       * The four CONTROLLED values are re-seeded from what the server actually stored, not from
       * what was typed: it trims, folds all-whitespace to null and re-serialises the date, and the
       * media ids only exist after the upload above.
       *
       * The uncontrolled text boxes are deliberately NOT re-seeded, and the difference is worth
       * stating. They are `defaultValue` inputs, so re-seeding them means remounting the form,
       * which would take this notice and the whole scroll position with it. Nothing on this body is
       * normalised beyond a trim — unlike `ReviewEditPanel`, which re-reads because the server
       * title-cases name columns and the next diff would otherwise be computed against the wrong
       * string. Here the only divergence is a trailing space the designer typed and the server
       * dropped, and re-sending it produces the identical stored value.
       */
      setPhotoId(saved.photoMediaId);
      setSignatureId(saved.signatureMediaId);
      setCvId(saved.cvMediaId);
      setStateName(saved.state ?? "");
      setPincode((saved.pincode ?? "").replace(/\D/g, "").slice(0, 6));
      /*
        THE CAPTURE CARDS KEEP WHAT DID NOT GO UP. These lines were `setPhotoFiles([])` and
        `setSignatureFiles([])`, unconditionally, and they ran on the path that had just written
        "the one already on file was kept" into the notice. The sentence was true about the COLUMN
        and false about the screen: the photograph the designer had attached — often taken on the
        spot, existing nowhere but in this browser's memory — was discarded by the same save that
        told them it had not been. Re-seeding with the stranded files means the notice explains a
        card that still holds the bytes it is talking about.

        All three are `[]` when everything landed, which is every ordinary save.
      */
      setPhotoFiles(strandedPhotos);
      setSignatureFiles(strandedSignatures);
      setCvFiles(strandedCvs);
      resetDirty();
      /*
        …EXCEPT while a photograph is still sitting in a capture card because its upload failed.
        `resetDirty` is right about the text boxes — they match what the server now holds — and
        wrong about the card: those bytes are unsaved work that exists nowhere else, and the back
        control is one click away. Both the leave guard and the "Unsaved changes" chip read this flag.

        AND THE SENTENCE IS RAISED WITH IT, in the same `if`, because a chip and a leave prompt on a
        save that announced itself as successful are unexplained on their own — that was the whole of
        the defect the first version of this left behind. One sentence for both cards rather than one
        per card: `uploadOne` has already named each file and each card, and repeating the retry twice
        when both a photograph and a signature failed reads like two different retries. Three cards
        now, and the argument only gets stronger with each one.
      */
      if (strandedPhotos.length || strandedSignatures.length || strandedCvs.length) {
        markDirty();
        troubles.push("Press Save again to send just that — this form saves in one PUT, so nothing is duplicated by retrying.");
      }
      setBackPromptOpen(false);
      onSaved(saved);
      setNotice(
        troubles.length
          ? `Profile saved. ${troubles.join(" ")}`
          : "Profile saved. New design workshops will be created with these details."
      );
    } catch (err) {
      // `apiFetch` has already turned FastAPI's 422 list into a sentence naming the field, so this
      // is the real message and not "[object Object]".
      setError(err instanceof Error ? err.message : "Unable to save this profile");
    } finally {
      setSaving(false);
    }
  }

  /**
   * The controls of each group, keyed by the group's stable key rather than by its heading.
   *
   * `Record<DesignerProfileGroupKey, …>` is what makes this exhaustive: adding a ninth group to
   * `DESIGNER_PROFILE_GROUPS` without adding its controls here stops the build, instead of shipping
   * a panel that draws a heading and nothing underneath it.
   */
  const groupBody: Record<DesignerProfileGroupKey, React.ReactNode> = {
    identity: (
      <>
        <Field label={DESIGNER_PROFILE_LABELS.displayName}>
          <TextInput name="displayName" defaultValue={profile.displayName ?? ""} maxLength={MAX.displayName} />
        </Field>
        <Field label={DESIGNER_PROFILE_LABELS.localName}>
          <TextInput name="localName" defaultValue={profile.localName ?? ""} maxLength={MAX.localName} />
        </Field>
        <Field label={DESIGNER_PROFILE_LABELS.designation}>
          <TextInput name="designation" defaultValue={profile.designation ?? ""} maxLength={MAX.designation} />
        </Field>
      </>
    ),
    institution: (
      <>
        <Field label={DESIGNER_PROFILE_LABELS.institution}>
          <TextInput name="institution" defaultValue={profile.institution ?? ""} maxLength={MAX.institution} />
        </Field>
        <Field label={DESIGNER_PROFILE_LABELS.department}>
          <TextInput name="department" defaultValue={profile.department ?? ""} maxLength={MAX.department} />
        </Field>
      </>
    ),
    qualifications: (
      <>
        <Field label={DESIGNER_PROFILE_LABELS.qualification}>
          <TextInput name="qualification" defaultValue={profile.qualification ?? ""} maxLength={MAX.qualification} />
        </Field>
        <Field label={DESIGNER_PROFILE_LABELS.specialisation}>
          <TextInput name="specialisation" defaultValue={profile.specialisation ?? ""} maxLength={MAX.specialisation} />
        </Field>
        <Field label={DESIGNER_PROFILE_LABELS.experienceYears}>
          <TextInput
            name="experienceYears"
            type="number"
            // Bounded HERE as well as on the server, and that is the whole reason the encoder does
            // not clamp: the column is validated 0–70 by pydantic and a rejection 422s the WHOLE
            // body, so 400 years typed into this box would lose the twenty fields the designer got
            // right. Native validation refuses the submit instead, on the box that is wrong.
            min={0}
            max={70}
            step={1}
            defaultValue={profile.experienceYears === null ? "" : String(profile.experienceYears)}
          />
        </Field>
      </>
    ),
    biography: (
      <div className="md:col-span-2">
        <Field label={DESIGNER_PROFILE_LABELS.biography}>
          <TextArea
            name="biography"
            rows={8}
            defaultValue={profile.biography ?? ""}
            maxLength={MAX.biography}
            className="min-h-40"
          />
        </Field>
      </div>
    ),
    contact: (
      <>
        {/* FieldBlock, not Field: PhoneField contains a themed dropdown, and a <label> wrapped
            around one forwards a stray click into the menu and slams it shut after one pick. */}
        <FieldBlock label={DESIGNER_PROFILE_LABELS.phone}>
          <PhoneField name="phone" defaultValue={profile.phone} onValueChange={markDirty} />
        </FieldBlock>
        <Field label={DESIGNER_PROFILE_LABELS.email}>
          {/* type="email" gives the browser its own inline validation, which matters here: the
              column is an EmailStr and a malformed address 422s the whole twenty-one-field body. */}
          <TextInput name="email" type="email" defaultValue={profile.email ?? ""} />
        </Field>
        <Field label={DESIGNER_PROFILE_LABELS.website}>
          <TextInput name="website" type="url" defaultValue={profile.website ?? ""} maxLength={MAX.website} />
        </Field>
      </>
    ),
    address: (
      <>
        <div className="md:col-span-2">
          <Field label={DESIGNER_PROFILE_LABELS.addressLine}>
            <TextInput name="addressLine" defaultValue={profile.addressLine ?? ""} maxLength={MAX.addressLine} />
          </Field>
        </div>
        <Field label={DESIGNER_PROFILE_LABELS.city}>
          <TextInput name="city" defaultValue={profile.city ?? ""} maxLength={MAX.city} />
        </Field>
        <FieldBlock label={DESIGNER_PROFILE_LABELS.state}>
          <Select
            name="state"
            value={stateName}
            onChange={(event) => {
              setStateName(event.target.value);
              // The themed dropdown is a <button> and fires no native input event, so the form's
              // onInput never sees it: the dirty flag has to be raised by hand or a changed state
              // leaves the page without a prompt.
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
            name="pincode"
            inputMode="numeric"
            // Six, not the column's 12: `pincodeValidationError` — the same function the artisan
            // form is judged by, and the same three sentences `address.py` answers with — demands
            // exactly six digits, so a box that accepted twelve would invite an answer the
            // validator underneath it refuses.
            maxLength={6}
            value={pincode}
            aria-invalid={Boolean(pincodeProblem)}
            onChange={(event) => setPincode(event.target.value.replace(/\D/g, "").slice(0, 6))}
          />
        </Field>
        {pincodeProblem || zoneProblem ? (
          <p className={`md:col-span-2 text-xs leading-5 ${pincodeProblem ? "text-error-600" : "text-amber-800"}`}>
            {pincodeProblem ?? zoneProblem}
          </p>
        ) : null}
      </>
    ),
    empanelment: (
      <>
        <Field label={DESIGNER_PROFILE_LABELS.empanelmentNo}>
          <TextInput name="empanelmentNo" defaultValue={profile.empanelmentNo ?? ""} maxLength={MAX.empanelmentNo} />
        </Field>
        {/* Not `Field`, which is a `<label>`: `DateField` carries a calendar button, and a wrapping
            label folds that button's own name into the input's — the field then announces itself as
            "Empanelment date Open calendar". The same reason `FieldBlock` is used for the phone and
            state controls above. */}
        <div className="grid min-w-0 gap-1">
          <label className="field-label" htmlFor="designer-empanelment-date">
            {DESIGNER_PROFILE_LABELS.empanelmentDate}
          </label>
          {/*
            `DateField` and not `<TextInput type="date">`. A native date input formats itself from the
            BROWSER's locale, not this app's, so an empanelment recorded as 02/03/2019 is February to
            a designer on en-IN and March to the administrator checking it on en-US — and since both
            readings are valid dates nothing anywhere reports a problem. `lib/designers.ts` already
            notes that this value is compared as a bare `yyyy-mm-dd` string, which is exactly what the
            hidden input below submits, so the wire format is unchanged.

            `onChange={markDirty}` is load-bearing and not decoration. The form raises its dirty flag
            from `onInput`, and picking a day out of the calendar grid sets React state rather than
            typing into the box, so no input event is ever dispatched — the same trap the state
            dropdown above documents. Without this, changing the empanelment date by calendar and then
            navigating away would be discarded with no unsaved-changes prompt at all.
          */}
          <DateField
            id="designer-empanelment-date"
            name="empanelmentDate"
            defaultValue={(profile.empanelmentDate ?? "").slice(0, 10)}
            onChange={markDirty}
          />
        </div>
      </>
    ),
    images: (
      <>
        <div className="md:col-span-2">
          <MediaSlot
            label={DESIGNER_PROFILE_LABELS.photoMediaId}
            help={DESIGNER_PROFILE_HELP.photoMediaId}
            mediaId={photoId}
            files={photoFiles}
            frameClassName="h-32 w-32"
            alt="Photograph of the designer"
            onFilesChange={(files) => {
              // Trimmed to the LAST file: the column holds one id, and a designer who attached three
              // photographs would otherwise have two of them uploaded, linked to nothing, and no way
              // to tell which one the report will print.
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
        <div className="md:col-span-2">
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
        {/*
          THE CV. A DocumentSlot rather than a MediaSlot, and the difference is only what it draws
          and what it accepts — the state, the trim-to-last rule, the upload and the save path are
          the same three lines the two slots above use, deliberately, because a second upload path
          for one column is a second thing to keep working offline.

          FULL WIDTH. A PDF preview at `h-32 w-32` is a thumbnail of a page of text, which answers
          nothing; the whole point of rendering it is that the designer can read it and see it is
          the right document and the right version.
        */}
        <div className="md:col-span-4">
          <DocumentSlot
            label={DESIGNER_PROFILE_LABELS.cvMediaId}
            help={DESIGNER_PROFILE_HELP.cvMediaId}
            mediaId={cvId}
            files={cvFiles}
            onFilesChange={(files) => {
              // Trimmed to the LAST file for the reason given at the photograph above: the column
              // holds one id, and a designer who attached two CVs would otherwise have one of them
              // uploaded, linked to nothing, and no way to tell which one the report will carry.
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
      {/*
        THE TWO ANSWERS THE SAVE BUTTON CAN GIVE, IN REGIONS THAT ARE MOUNTED BEFORE THEY HAVE
        ANYTHING TO SAY.

        Both boxes used to be `{error ? <div…> : null}` with no live role at all, so the only reader
        who learned what "Save your profile" had done was one who could see the box appear. Pressing
        Save moves nothing, focuses nothing and disables the button for the length of one PUT — so a
        designer using a screen reader pressed it, heard silence, and could not tell a 422 on their
        e-mail address from a save that worked. Adding `role` to the box itself does not fix that:
        assistive technology announces mutations only inside a region that ALREADY EXISTED when the
        page settled, which is why the region is this wrapper and the box is inserted INTO it. Same
        rule, same reason, as `CollectionTable`'s always-mounted status region and `Toast`'s
        permanently-present viewport.

        `alert` for the refusal and `status` for the notice, chosen by what the reader has to do:
        nothing was saved and something must be retyped, versus the save landed (with, on the
        partly-failed path, a sentence naming which capture card still holds bytes and that pressing
        Save again is safe).

        `sr-only` AND NOT `hidden`, AS A CLASS SWAP ON ONE ELEMENT — the idiom `SubmissionCard` uses
        and the trap its header names: `display: none` takes an element out of the accessibility tree,
        so a `hidden` or `empty:hidden` region is no better than one that did not exist. Empty, each
        box is absolutely positioned and 1×1, which is also why `mb-4` can stay on it — a form with
        nothing to report does not push its first panel down.
      */}
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

      <p className="mb-5 rounded-md border border-line-200 bg-surface-50 px-4 py-3 text-sm leading-6 text-ink-muted">
        {DESIGNER_PROFILE_COPY_NOTICE}
      </p>

      <form ref={formRef} onSubmit={submit} onInput={markDirty} onKeyDown={handleFormEnter} className="grid gap-5">
        {DESIGNER_PROFILE_GROUPS.map((group) => (
          <section key={group.title} className="panel grid gap-3 p-4">
            <div>
              <h2 className="font-display text-lg font-bold text-ink-900">{group.title}</h2>
              {group.blurb ? <p className="mt-1 text-sm leading-6 text-ink-muted">{group.blurb}</p> : null}
            </div>
            <div className="grid gap-3 md:grid-cols-2">{groupBody[group.key]}</div>
            {/* The help lines for this group's fields, printed once beneath it rather than under
                every box: ten of the twenty-one have something to say and hanging a sentence under
                each one turns a form into a wall of prose. */}
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
          // Dropping the flag first is what lets the navigation through: the interceptor claims the
          // back control only while the form is dirty.
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

/** The `help` sentences for one group, in field order, each naming its own field. */
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

/**
 * One single-slot image column: what is stored now, a way to remove it, and the attach card.
 *
 * The stored image and the attach card are shown TOGETHER rather than one replacing the other, so a
 * designer can see the photograph that is currently on their reports while choosing the one that
 * will replace it. Removing is explicit and separate from attaching, because "I want no photograph"
 * and "I want a different photograph" are different intentions and a single control cannot express
 * both.
 */
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
          "One image. It uploads as soon as it is attached; saving the profile then links it to this column."
        }
        allowedTypes={["IMAGE"]}
        allowDocuments={false}
      />
    </div>
  );
}

/**
 * The document twin of {@link MediaSlot} — one uploaded file, rendered where the browser can render
 * it.
 *
 * ── WHY IT IS A SEPARATE COMPONENT AND NOT A FLAG ON `MediaSlot` ────────────────────────────────
 *
 * Three of `MediaSlot`'s own decisions are wrong for a document and would each need a branch:
 * `StoredMediaImage` is an `<img>` and a PDF is not one; `frameClassName` is a fixed square because
 * a portrait and a signature are known shapes, whereas a document wants the full column; and
 * `allowedTypes={["IMAGE"]}` / `allowDocuments={false}` is precisely what has to invert. Three
 * branches through one component, all keyed on the same boolean, is two components written badly.
 *
 * ── WHAT IT SHARES, WHICH IS EVERYTHING THAT MATTERS ────────────────────────────────────────────
 *
 * The same `MediaCaptureField` (eager pre-upload, per-file progress, independent retry, offline
 * staging), the same "attaching another replaces it" contract, and the same explicit Remove — kept
 * separate from attaching because "I want no CV" and "I want a different CV" are different
 * intentions and one control cannot express both.
 */
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
      {/*
        `noun` IS THE LABEL VERBATIM, CASE AND ALL, because `DocumentPreview` interpolates it
        unchanged and four of its five uses START A SENTENCE with it: "No CV on file.", "Loading the
        CV…", "This CV is no longer readable from here.". Lower-casing it here printed "No cv on
        file." on the one screen a designer types on, while `DesignerProfileView` and the handset's
        `DwDocumentPreview` both pass "CV" for the same column — three surfaces, one field, and the
        editor the odd one out. The comment that stood here asserted the capitalised outcome the code
        did not produce, which is worse than no comment: it is what stops the next reader looking.

        It is still deliberately the SAME word the label uses — a page that calls it "CV" above the
        box and "curriculum vitae" inside it is a page with two names for one thing. The `title`
        below builds its own phrase from the same label and is composed here rather than interpolated
        into somebody else's sentence, which is why the two treat the case differently.
      */}
      <DocumentPreview mediaId={mediaId} noun={label} className="h-[30rem]" />
      <MediaCaptureField
        files={files}
        onFilesChange={onFilesChange}
        title={mediaId ? `Replace ${label.toLowerCase()}` : `Attach ${label.toLowerCase()}`}
        description={
          help ??
          "One document. It uploads as soon as it is attached; saving the profile then links it to this column."
        }
        /*
          ALL THREE TOKENS ARE LOAD-BEARING AND `PDF` IS THE ONE THAT BITES.

          `addFiles` FILTERS the selection through `inferMediaType`, which answers `"PDF"` — not
          `"DOCUMENT"` — for `application/pdf`. So `["DOCUMENT", "IMAGE"]`, which is the obvious list
          to write here, would have silently dropped every PDF: the format this box is mostly FOR,
          the one its help text names first, and the only one the page can render inline. The file
          chooser would have offered `.pdf` (it is in `documentAccept`), accepted the pick, and
          discarded it with nothing on screen. The token's whole job is `addFiles`' `inferMediaType`
          filter and NOT the chooser: this card states its own `accept` two props below, which
          REPLACES the joined `ACCEPT_BY_TYPE` list outright, so `ACCEPT_BY_TYPE.PDF` is still reached
          by nothing and `.pdf` gets into this dialog from the explicit list instead.

          IMAGE IS OFFERED BESIDE THEM, and that is not sloppiness either. A designer whose CV exists
          as a photographed or scanned sheet — common in this fieldwork — would otherwise be told
          their own CV is the wrong kind of file, with no way to attach it at all. A photographed
          sheet is an IMAGE to `inferMediaType`, so `DocumentPreview` renders it through the same
          non-PDF path as a .docx: named, sized, and openable.

          AND IT REACHES A REPORT EXACTLY AS A PDF DOES — WHICH IS TO SAY BY NAME AND NOT BY BYTES.
          This comment used to end "the report carries it as an annexure either way", and that is
          false for every format including the PDF: `report_builder._image_sources` admits IMAGE and
          IMAGE_LIST only, `_render_media_annexure` gathers through `_images`, and this column is
          copied into `designerCv`, which the registry declares as a FILE. So a report NAMES the
          attachment and `build_report` warns beside the download that the file itself is not inside
          it. Nothing on this card may promise otherwise; the sentence a designer reads here comes
          from `DESIGNER_PROFILE_HELP.cvMediaId` in `profileCopy.ts`, which is where that promise has
          to be corrected rather than contradicted from a second surface.
        */
        allowedTypes={["PDF", "DOCUMENT", "IMAGE"]}
        /*
          AND THE CHOOSER IS NARROWED SEPARATELY, BECAUSE THE `DOCUMENT` TOKEN ABOVE IS WIDER THAN
          THIS BOX MEANS.

          The paragraph above explains why `DOCUMENT` has to be named: `inferMediaType` answers
          `"DOCUMENT"` for a `.docx`, so without the token `addFiles` would drop one. But naming it
          hands the chooser `documentAccept` — every FILE field's whole attachment list, `.txt`,
          `.csv`, `.xls`, `.xlsx`, `.json`, `.glb`, `.gltf` included — and `addFiles` admits all of
          those too, because they infer as DOCUMENT as well. What that bought was a spreadsheet or a
          3D model stored in a column called CV: a format neither help sentence names, and one the
          handset's own picker cannot even select.

          THE LIST IS ANDROID'S MIME ARRAY, FORMAT FOR FORMAT (`DesignerProfileScreen.kt`'s
          `pickDocument.launch`: `application/pdf`, `application/msword`, the `wordprocessingml`
          and `opendocument.text` pair, `image/*`). The two clients offer one column the same
          formats or they do not agree about what a CV is, and this is the surface that was wider.

          IT NARROWS THE DIALOG, NOT THE ACCEPTANCE — see the prop's own note. A drop still arrives
          through `addFiles`, which filters against `allowedTypes`, so this is the polite half and
          the three tokens above are still the load-bearing one.
        */
        accept=".pdf,.doc,.docx,.odt,image/*"
        allowDocuments
      />
    </div>
  );
}

/** A FormData value as the trimmed string, or "" — the encoder folds "" to null. */
function text(form: FormData, key: string): string {
  const value = form.get(key);
  return typeof value === "string" ? value.trim() : "";
}

/** What one image column's upload attempt produced — see {@link uploadOne}. */
type OneUpload = {
  /** The id to write into the profile column, or null when nothing landed. */
  mediaId: string | null;
  /**
   * The files that did NOT land, as the very `File` objects the caller passed.
   *
   * THE CALLER PUTS THESE BACK IN THE CAPTURE CARD, and that is the whole reason this is returned
   * rather than counted. See the note on the save path below for what clearing them cost.
   */
  stranded: File[];
};

/**
 * Upload one image and report both what landed and what did not, appending a sentence to `troubles`
 * for anything that did not make it.
 *
 * `uploadMediaBatch` RESOLVES on a partly-failed batch and throws only when nothing landed at all,
 * so a caller that treats a resolved promise as success loses files without a word. The names of
 * the ones that failed are collected rather than thrown, because the other twenty fields on this
 * form are still worth saving and a failed photograph must not take them down with it.
 *
 * IT READS `outcomes`, NOT `uploaded`/`failed`, and the difference is the `File`. `failed` carries a
 * NAME, and a name cannot be matched back to an input file — two photographs off one handset are
 * routinely both IMG_0001.jpg. `outcomes` carries the object, so the caller can re-seed the capture
 * card with exactly the bytes that still have to go somewhere.
 */
async function uploadOne(files: File[], userId: string, caption: string, troubles: string[]): Promise<OneUpload> {
  try {
    const { outcomes } = await uploadMediaBatch({
      files,
      linkedRecordType: DESIGNER_PROFILE_MEDIA_RECORD_TYPE,
      linkedRecordId: userId,
      caption,
      // A photograph and a signature are stills; queueing them for speech transcription would spend
      // a provider call on an image and put an empty transcript on the row.
      transcribeAudio: false
    });
    const stranded = outcomes.filter((outcome) => outcome.failure !== null);
    if (stranded.length) {
      troubles.push(
        `The ${caption.toLowerCase()} did not upload (${stranded
          .map((outcome) => outcome.file.name)
          .join(", ")}), so the one already on file was kept and the new one is still attached below.`
      );
    }
    return {
      mediaId: outcomes.find((outcome) => outcome.media !== null)?.media?.id ?? null,
      stranded: stranded.map((outcome) => outcome.file)
    };
  } catch (err) {
    troubles.push(
      `The ${caption.toLowerCase()} did not upload (${err instanceof Error ? err.message : "the transfer failed"}), so the one already on file was kept and the new one is still attached below.`
    );
    // A THROW FROM `uploadMediaBatch` MEANS NOTHING LANDED, so every file handed in is still owed a
    // retry and every one of them goes back to the caller.
    return { mediaId: null, stranded: files };
  }
}
