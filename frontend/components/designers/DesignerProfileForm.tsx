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

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { IdCard, Trash2 } from "lucide-react";

import { StoredMediaImage } from "@/components/designers/StoredMediaImage";
import {
  DESIGNER_PROFILE_COPY_NOTICE,
  DESIGNER_PROFILE_GROUPS,
  DESIGNER_PROFILE_HELP,
  DESIGNER_PROFILE_LABELS,
  isDesignerProfileFieldRequired,
  type DesignerProfileGroupKey
} from "@/components/designers/profileCopy";
import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";
import { Field, Select, TextInput } from "@/components/FormControls";
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

      // The second string is the mid-sentence phrase, not a lower-cased copy of the first: "CV" is
      // an acronym and keeps its case wherever it appears. See {@link uploadOne}.
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
        {/*
          REQUIRED, NATIVELY, ON A PLAIN TEXT INPUT — which is the one control on this form where
          `required` needs no argument at all. It is a real `<input>` inside the `<form>`, so the
          browser refuses the submit and anchors its own bubble to the box that is empty. The three
          other mandatory answers each sit on a themed control and each carries its own note.

          `Field` prints the asterisk from the same boolean, so the mark a reader sees and the rule
          the browser enforces cannot drift apart.
        */}
        <Field label={DESIGNER_PROFILE_LABELS.displayName} required={isDesignerProfileFieldRequired("displayName")}>
          <TextInput
            name="displayName"
            defaultValue={profile.displayName ?? ""}
            maxLength={MAX.displayName}
            required={isDesignerProfileFieldRequired("displayName")}
          />
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
        <Field label={DESIGNER_PROFILE_LABELS.qualification} required={isDesignerProfileFieldRequired("qualification")}>
          <TextInput
            name="qualification"
            defaultValue={profile.qualification ?? ""}
            maxLength={MAX.qualification}
            required={isDesignerProfileFieldRequired("qualification")}
          />
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
        {/*
          THE ONE NARRATIVE BOX ON THIS FORM, AND THEREFORE THE FIRST OF THE TWO THAT GETS A
          MICROPHONE. See `DictatedField` below for the whole argument about which boxes do and do
          not, and why the button cannot live inside `Field`.
        */}
        <DictatedField
          name="biography"
          label={DESIGNER_PROFILE_LABELS.biography}
          defaultValue={profile.biography ?? ""}
          maxLength={MAX.biography}
          rows={8}
          onDirty={markDirty}
        />
      </div>
    ),
    contact: (
      <>
        {/* FieldBlock, not Field: PhoneField contains a themed dropdown, and a <label> wrapped
            around one forwards a stray click into the menu and slams it shut after one pick. */}
        <FieldBlock label={DESIGNER_PROFILE_LABELS.phone} required={isDesignerProfileFieldRequired("phone")}>
          {/*
            `required` LANDS ON THE VISIBLE NUMBER BOX, NEVER ON THE MIRROR — see `PhoneField`'s own
            note on the prop. The mirror is `h-0 w-0 opacity-0`, so a refusal anchored there is a
            validation bubble pointing at a box nobody can see; the visible input is an ordinary
            `<input>` in this same form and enforces the identical rule where the answer goes.
          */}
          <PhoneField
            name="phone"
            defaultValue={profile.phone}
            onValueChange={markDirty}
            required={isDesignerProfileFieldRequired("phone")}
          />
        </FieldBlock>
        <Field label={DESIGNER_PROFILE_LABELS.email} required={isDesignerProfileFieldRequired("email")}>
          {/*
            THE PLATFORM'S EMAIL RULE AND NOTHING ELSE, WHICH IS HOW THE CLIENT AGREES WITH THE
            SERVER RATHER THAN INVENTING A SECOND OPINION.

            The column is `EmailStr` (`backend/app/schemas/designers.py`), so a malformed address
            422s the whole twenty-one-field body and takes twenty correct answers down with it.
            `type="email"` is what catches that before the round trip — it is the WHATWG rule, so an
            address bearing an `@` and a domain that the server would accept is not refused here, and
            one with no `@` at all never leaves the browser.

            NO `setCustomValidity` ON THIS BOX, DELIBERATELY, and that is §12.8's Aadhaar trap read
            the right way round: with BOTH a native `required` and a custom validity message set, it
            is up to the browser which of the two sentences it shows, so the field would sometimes
            report the wrong fault. The box is required, so `required` is the attribute that stands
            and the type mismatch is left to the platform's own wording.

            AND NO HAND-WRITTEN REGEX ANYWHERE IN THIS FILE. A second email rule is a rule that can
            disagree with `EmailStr`, and the direction it disagrees in — refusing an address the
            server would have stored — is the one a designer cannot work around.
          */}
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
        <div className="md:col-span-2">
          {/*
            THE SECOND MICROPHONE, AND IT IS THE SAME BOX `/artisans/new` PUTS ONE ON. `ArtisanForm`
            mounts `DictatedTextArea` on its address for a reason that holds identically here: an
            address is the one multi-part answer somebody standing in a courtyard would rather speak
            than thumb in, and a formatting toolbar on it would be an invitation to store a document
            in a column four exports print as a delivery address.

            SINGLE-LINE, THOUGH, WHERE THE ARTISAN'S IS A TEXTAREA. `Artisan.address` has always held
            newlines; `DesignerProfile.addressLine` has not, and it is copied into a registry field
            and typeset on a report cover. Changing the shape of what is stored is not what the
            owner asked for and is not something this form can verify downstream, so the box stays as
            it was and only gains the button. `rows` is therefore not passed.
          */}
          <DictatedField
            name="addressLine"
            label={DESIGNER_PROFILE_LABELS.addressLine}
            defaultValue={profile.addressLine ?? ""}
            maxLength={MAX.addressLine}
            onDirty={markDirty}
          />
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
        {/*
          ── EVERY WRAPPER IN THIS GROUP SPANS `md:col-span-2`, WHICH IS THE WHOLE OF THE GRID ──────

          THE DEFECT THIS REPLACES, because it is the least obvious grid bug in the app and it made
          the photograph, the signature and the CV cards unusable at once.

          The section's grid is `grid gap-3 md:grid-cols-2` — TWO tracks, `repeat(2, minmax(0, 1fr))`.
          The CV wrapper used to be `md:col-span-4`. A grid item may not span past the explicit grid
          and simply be clamped: CSS Grid auto-placement ADDS IMPLICIT COLUMNS to accommodate the
          largest span, and an implicit column is sized by `grid-auto-columns`, which is `auto`. So
          the one `col-span-4` silently turned a two-column grid into FOUR tracks —
          `minmax(0,1fr) minmax(0,1fr) auto auto` — and two things went wrong together:

            1. AUTO-PLACEMENT PUT THE SIGNATURE CARD IN THE IMPLICIT PAIR. With four tracks, the
               photograph took columns 1–2 and the signature no longer needed a new row: it fitted
               beside it in columns 3–4. Measured in Chromium at a 1280px viewport against the real
               card markup, the tracks resolved to `482px 482px 107px 107px` — a photograph card
               976px wide and a signature card 226px wide, two things that are meant to be identical
               full-width rows.
            2. THE `1fr` TRACKS LOSE TO THE `auto` ONES. `fr` distributes only the FREE space left
               after intrinsic tracks are sized, and a spanning item's intrinsic contribution is
               distributed to the non-flexible tracks — so as the implicit pair grew, tracks 1–2
               shrank towards nothing. At the point they reach 0 the item spanning them measures
               exactly one `gap-3`: 12px, holding 127px of content. That is the 12px column reported
               on the live page, reproduced to the pixel.

          `min-w-0` IS NOT WHAT WAS WRONG HERE, and reaching for it is the trap. It stops an item
          refusing to shrink below its content; it cannot stop a track from being created, and a
          zero-width track is already as shrunk as it gets. It is added below as an ordinary belt
          — a `MediaCaptureField` and a rendered PDF are wide content in a grid item — but the CAUSE
          was the span, and only changing the span fixes it.

          THE CV IS STILL FULL WIDTH. It always wanted the whole row and it still gets it: on this
          grid the whole row IS `col-span-2`. A PDF preview at half a row is a thumbnail of a page of
          text, which answers nothing.
        */}
        <div className="min-w-0 md:col-span-2">
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
        {/*
          THE CV. A DocumentSlot rather than a MediaSlot, and the difference is only what it draws
          and what it accepts — the state, the trim-to-last rule, the upload and the save path are
          the same three lines the two slots above use, deliberately, because a second upload path
          for one column is a second thing to keep working offline.

          FULL WIDTH, WHICH ON THIS GRID IS `col-span-2` AND NOT `col-span-4` — the block comment at
          the top of this group has the measurements and the reason. A PDF preview at `h-32 w-32` is
          a thumbnail of a page of text, which answers nothing; the whole point of rendering it is
          that the designer can read it and see it is the right document and the right version.
        */}
        <div className="min-w-0 md:col-span-2">
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

/**
 * A free-text box with a microphone under it — the designer profile's half of the control
 * `/artisans/new` calls `DictatedTextArea`.
 *
 * ── WHY THIS PAGE HAD NO MICROPHONE AT ALL, AND WHAT IT IS COPYING ──────────────────────────────
 *
 * Dictation reached the record forms (`DictatedTextArea`, `RichTextField`) and the design-workshop
 * stage forms (`FieldInput`'s `DictationButton`), and this screen — twenty-one columns, several of
 * them prose, typed by somebody who is usually not at a desk — was simply never given one. The
 * owner reported it as the page being the odd one out, and it was. `OnDeviceDictationButton` is the
 * exact control the artisan form mounts: on-device recognition, no `MediaRecorder`, no network, no
 * consent model to answer to, and its own sentence on a browser that cannot dictate rather than a
 * control that silently is not there.
 *
 * ── WHY IT IS NOT `DictatedTextArea` ITSELF ─────────────────────────────────────────────────────
 *
 * That component is a `<textarea>` and nothing else, and one of the two boxes here is single-line
 * (the address — see its call site for why the stored shape is not being changed). It also takes no
 * `maxLength`, and every text column on this profile is bounded by the backend schema, which the
 * `MAX` table at the top of this file mirrors precisely so a designer meets the ceiling in the box
 * rather than as a 422 that discards the other twenty answers. So this renders the same three parts
 * in the same order and keeps the props this form needs.
 *
 * ── AND IT IS NOT A `Field` ────────────────────────────────────────────────────────────────────
 *
 * `Field` is a `<label>`, and a `<label>` forwards a stray click to the first labelable control
 * inside it. With the microphone under the box, clicking "Dictate" would ALSO focus the textarea —
 * which on a phone throws the on-screen keyboard up over the readout the designer is watching. It
 * is the same reason `DictatedTextArea` writes its own `<label htmlFor>`, and the same reason the
 * empanelment date above this does.
 *
 * ── WHY TWO BOXES AND NOT TWENTY-THREE ─────────────────────────────────────────────────────────
 *
 * Dictation is offered where the answer is PROSE — the biography paragraph and the address — and
 * nowhere else, which is exactly the split `/artisans/new` makes (address and notes get one; name,
 * phone and email do not). A recogniser writes "at" for `@`, spells digits out in words and
 * punctuates a URL, so a microphone under the e-mail, phone, pincode, website and date boxes would
 * be a control that reliably produces a value the field then refuses. And a form whose every row
 * carries a button is a form where the button stops being noticed.
 */
function DictatedField({
  name,
  label,
  defaultValue,
  maxLength,
  rows,
  onDirty
}: {
  name: string;
  label: string;
  defaultValue: string;
  maxLength: number;
  /** Omit for a single-line box. A `rows` of 1 is still a textarea, and an address is not one. */
  rows?: number;
  /** The form's `markDirty`. A dictated phrase is a React state write, not a typed `input` event. */
  onDirty: () => void;
}) {
  const reactId = useId();
  const boxId = `${reactId}-box`;
  const fullId = `${reactId}-full`;

  /*
    CONTROLLED, which the rest of this form's text boxes deliberately are not.

    Dictation writes into the box from OUTSIDE the keyboard, and an uncontrolled input would need a
    ref plus a hand-dispatched `input` event to keep React and the DOM agreeing about what is in it.
    `submit` reads `FormData` off the form element, so nothing downstream can tell the difference —
    and the save path's note about not re-seeding the uncontrolled boxes is unaffected for the same
    reason: the server normalises nothing here beyond a trim.
  */
  const [value, setValue] = useState(defaultValue);
  const full = value.length >= maxLength;

  /*
    `onDirty` FIRES FROM THE TWO PLACES THAT CHANGE THE VALUE, never from an effect watching it. An
    effect would fire once on mount, when the box is seeded from the stored profile, and every visit
    to this page would then pop the unsaved-changes dialog on the way out of a record nobody touched.
    Designers learn to click through that dialog, and then it stops protecting anything.

    ── AND THE CEILING IS ENFORCED HERE, NOT ONLY BY THE `maxLength` ATTRIBUTE ────────────────────

    A DOM `maxLength` bounds TYPING and PASTING and has no opinion at all about a value written into
    React state, which is exactly what a committed phrase is. So dictation was the one path that
    could carry the address past its column's 300 characters, and an over-long value 422s the WHOLE
    twenty-one-key body — the designer loses twenty correct answers because they spoke one sentence
    too many, with the refusal naming a box that looks fine on screen.

    IT IS CLAMPED AND THEN SAID, never clamped quietly: a box that silently stops accepting words is
    indistinguishable from a microphone that stopped working, which is rule 10 wearing a headset.
  */
  function update(next: string) {
    setValue(next.slice(0, maxLength));
    onDirty();
  }

  return (
    // `min-w-0` for the reason `Field` carries it: a grid item will not shrink below its content's
    // intrinsic width unless told to, and both of these boxes sit in a `md:col-span-2` cell.
    <div className="grid min-w-0 gap-1">
      <label className="field-label" htmlFor={boxId}>
        {label}
      </label>
      {rows === undefined ? (
        <input
          id={boxId}
          name={name}
          type="text"
          maxLength={maxLength}
          value={value}
          aria-describedby={full ? fullId : undefined}
          className="field-input"
          onChange={(event) => update(event.target.value)}
        />
      ) : (
        <textarea
          id={boxId}
          name={name}
          rows={rows}
          maxLength={maxLength}
          value={value}
          aria-describedby={full ? fullId : undefined}
          className="field-input min-h-40"
          onChange={(event) => update(event.target.value)}
        />
      )}
      {/*
        COMMITTING APPENDS, NEVER REPLACES — the recogniser is stopped and started many times across
        a long answer, and a commit that overwrote the box would delete everything already in it the
        moment somebody paused for breath. The join is a single space unless the box already ends in
        whitespace, or a paragraph dictated in five goes comes out as "…the warpis sized…". Copied
        from `DictatedTextArea` because it is the same rule and it must not be re-derived.
      */}
      <OnDeviceDictationButton
        fieldLabel={label}
        onCommit={(phrase) => {
          const joiner = !value || /\s$/.test(value) ? "" : " ";
          update(`${value}${joiner}${phrase}`);
        }}
      />
      {/*
        THE CEILING, SAID ON SCREEN WHEN IT IS REACHED — see `update`. Mounted only when it is true
        rather than swapped to `sr-only`: this is not a live region reporting the outcome of an
        action a reader is waiting on (which is why the save's two answer boxes are), it is a
        description of the box, so it is bound with `aria-describedby` and read when the box is
        focused. `ink-500`, not `error-600`: nothing is wrong and nothing was lost — the value in the
        box is exactly what will be saved.
      */}
      {full ? (
        <p id={fullId} className="text-xs leading-5 text-ink-500">
          This box is full — it holds {maxLength.toLocaleString("en-IN")} characters, which is what the
          column stores. Anything spoken or typed beyond that is not added.
        </p>
      ) : null}
    </div>
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
        box and "curriculum vitae" inside it is a page with two names for one thing.
      */}
      <DocumentPreview mediaId={mediaId} noun={label} className="h-[30rem]" />
      <MediaCaptureField
        files={files}
        onFilesChange={onFilesChange}
        /*
          THE LABEL VERBATIM HERE TOO — `.toLowerCase()` PRINTED `<h3>Attach cv</h3>`.

          It read as a sentence-cased phrase and it is not one: this slot's label is an ACRONYM, and
          lower-casing an acronym does not make it read naturally, it makes it read as a typo. The
          owner reported exactly this ("cv is written instead of CV"), and it was the single place on
          the page that disagreed — five other labels say "CV" correctly, including the group heading
          and the `DocumentPreview` line four lines above, whose comment had already worked this out
          for the same reason and stopped one call short.

          `MediaSlot`'s twin still lower-cases, and correctly: "Attach photograph" and "Attach
          signature" are ordinary nouns. The rule is about the WORD, not about the position, which is
          why the two components differ rather than sharing a helper that would have to guess.
        */
        title={mediaId ? `Replace ${label}` : `Attach ${label}`}
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
 *
 * ── `caption` AND `subject` ARE TWO STRINGS BECAUSE THEY ARE TWO JOBS ────────────────────────────
 *
 * `caption` is STORED, on the `MediaFile` row, and reads as a title: "Designer CV". `subject` is
 * READ, in the middle of the sentence below: "The designer CV did not upload…". This used to be one
 * argument lower-cased at the point of use, which is fine for "Designer photograph" and prints
 * "the designer cv did not upload" for the acronym — the same defect, in a sentence, that the CV
 * card's own `title` shipped as a heading. Composing the phrase at the call site is what lets each
 * word keep the case it is supposed to have; a `.toLowerCase()` cannot know which words are names.
 */
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
      // A photograph and a signature are stills; queueing them for speech transcription would spend
      // a provider call on an image and put an empty transcript on the row.
      transcribeAudio: false
    });
    const stranded = outcomes.filter((outcome) => outcome.failure !== null);
    if (stranded.length) {
      troubles.push(
        `The ${subject} did not upload (${stranded
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
      `The ${subject} did not upload (${err instanceof Error ? err.message : "the transfer failed"}), so the one already on file was kept and the new one is still attached below.`
    );
    // A THROW FROM `uploadMediaBatch` MEANS NOTHING LANDED, so every file handed in is still owed a
    // retry and every one of them goes back to the caller.
    return { mediaId: null, stranded: files };
  }
}
