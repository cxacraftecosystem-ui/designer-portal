/**
 * The words the designer profile is asked and read back in — declared ONCE for the editor and the
 * read-only view alike.
 *
 * The two screens render the same twenty-one columns in the same eight groups, and they must call
 * them the same thing: an admin reading a colleague's profile and that colleague editing it are looking
 * at one record, and a label that drifted between the two would make a filled field look like a
 * different, empty one. `lib/designers.DESIGNER_PROFILE_FIELDS` owns the field list; this file owns
 * what each of them is called on screen.
 *
 * NO LABEL HERE IS A FREE CHOICE ANY MORE, AND THAT CHANGED ON 2026-08-25. It used to be four:
 * `displayName`, `institution`, `biography` and `experienceYears` were the only columns
 * `prefill_from_profile` copied into a workshop's stage entries, so they were the only four printed
 * under the registry's own labels. The owner's instruction — everything typed on this page is master
 * data and is pre-filled into EVERY report — widened `PREFILL_MAP` to all twenty-one columns, so
 * every box on this screen now has a registry field it lands in and a report label it prints under.
 *
 * The rule that follows is unchanged in kind and wider in scope: where the registry's label reads
 * naturally as the label of a box on the designer's own profile it is used VERBATIM, curly
 * apostrophes and all, exactly as `stage_definitions.py` declares it. Where it would be odd here
 * ("Designer" as the label of a name box on your own profile; "Designer’s phone" on a page that is
 * entirely about you) the box is named plainly and the HELP text names the registry field the value
 * lands in. Either way a designer can trace a value on their report back to the box it came from,
 * which is the whole requirement — and a mismatch is caught rather than trusted: the backend's
 * `test_every_prefilled_profile_column_has_a_receiving_field` proves every target exists, and
 * `test_every_writable_profile_column_is_either_prefilled_or_named_here` proves none was forgotten.
 *
 * THE COPY IS A COPY, AND THE HELP SAYS SO. A workshop reads the profile once, at creation, and
 * never again — a report is a historical document, and a designer who moves from NIFT to NID in
 * 2027 must not retroactively rewrite the institution that sponsored their 2026 workshop. That is
 * counter-intuitive enough ("I fixed my institution, why is the old one still on the report?") that
 * it is said on the page rather than left to be discovered.
 */

import type { DesignerProfileField } from "@/lib/designers";

export const DESIGNER_PROFILE_LABELS: Record<DesignerProfileField, string> = {
  displayName: "Name",
  localName: "Name in the local script",
  designation: "Designation",
  institution: "Institution",
  department: "Department",
  qualification: "Qualification",
  specialisation: "Specialisation",
  // Verbatim from the registry (`designerExperience`), which is where this number is printed.
  experienceYears: "Designer’s experience",
  // Verbatim from the registry (`designerProfile`), the stage-3 heading this paragraph appears under.
  biography: "Designer’s profile",
  phone: "Phone",
  email: "Email",
  website: "Website",
  addressLine: "Address",
  city: "City or town",
  state: "State",
  pincode: "Pincode",
  photoMediaId: "Photograph",
  signatureMediaId: "Signature",
  cvMediaId: "CV",
  empanelmentNo: "Empanelment number",
  empanelmentDate: "Empanelment date"
};

/**
 * The four boxes that must be answered before this profile can be saved.
 *
 * ── DECLARED ONCE, HERE, BECAUSE FOUR PLACES HAVE TO AGREE ABOUT IT ──────────────────────────────
 *
 * The editor reads this array to mark its boxes and to set the native `required` attribute; the
 * read-only view reads it to say which blanks are the ones that stop a report; the server refuses
 * the same four columns when a body clears them (`DesignerProfileUpdate` in
 * `backend/app/schemas/designers.py`, whose `REQUIRED_COLUMNS` is the mirror of this list); and
 * `frontend/e2e/designer-profile-unit.spec.ts` diffs the two. A rule the client alone enforces is a
 * rule the API does not have, and the API is what the handset talks to as well.
 *
 * ── WHY THESE FOUR AND NOT THE OTHER SEVENTEEN ───────────────────────────────────────────────────
 *
 * The owner's instruction: "Name, qualification, email, and phone number should be mandatory fields
 * as well." Each is a value a report is submitted UNDER or a way of reaching the person who signed
 * it — the identity half of the record — where the other seventeen are description. Marking every
 * box required would mean a designer who has not yet been given an empanelment number cannot save
 * their biography, which is the failure `LocationFields` records as "a field may only be mandatory
 * where it is answerable".
 *
 * ORDER IS SCREEN ORDER, so a reader comparing this array against the form reads them in the same
 * sequence: name (identity), qualification (qualifications), phone and email (contact).
 */
export const DESIGNER_PROFILE_REQUIRED_FIELDS: readonly DesignerProfileField[] = [
  "displayName",
  "qualification",
  "phone",
  "email"
];

/** Whether a box on this profile must be answered — the one test both screens ask. */
export function isDesignerProfileFieldRequired(field: DesignerProfileField): boolean {
  return DESIGNER_PROFILE_REQUIRED_FIELDS.includes(field);
}

/** The sentence under a box: what it is for, and where the value ends up. */
export const DESIGNER_PROFILE_HELP: Partial<Record<DesignerProfileField, string>> = {
  // ⚠ THIS SENTENCE ENDED "Left blank, the cover falls back to the name on your account." — which
  // was true of the SERVER and stopped being true of the FORM the day this box became mandatory.
  // The fallback still exists for the rows that predate the rule (the profile row is created empty
  // by the GET itself, so a great many hold no name at all), but a required box whose help text
  // offers leaving it blank as an option is copy arguing with the control above it, and the reader
  // believes the copy.
  displayName: "Printed as “Designer” on the cover of every report you generate.",
  localName: "Printed verbatim, in whatever script you type it in.",
  institution: "Printed as “Designer’s institution” on the report cover.",
  experienceYears: "Whole years, 0 to 70 — the same range the report’s own field accepts.",
  biography: "The paragraph that appears as “Designer’s profile” in stage 3 of every report.",
  pincode: "Six digits. Optional, and never guessed for you.",
  photoMediaId: "One photograph. Attaching another replaces it.",
  // ⚠ "for the report's signature block" WAS FALSE, and it is the second false promise this one
  // sentence pair carried. `report_model.SignatureBlock` holds `signatories: tuple[tuple[str, str]]`
  // — a name and a role, two strings — and both .docx writers and both .pdf writers draw those names
  // over ruled lines. There is no image slot in it on either side of the wire, and
  // `designerSignature` is declared `report_role=GALLERY`, so the picture prints with the report's
  // photographs under its own heading. The registry's own help says exactly that; this now agrees
  // with it instead of promising a block the model cannot express.
  signatureMediaId:
    "One image of your signature. It prints with the report’s photographs, under its own heading. Attaching another replaces it.",
  cvMediaId:
  // ⚠ THIS SENTENCE ENDED "It reaches your reports as an annexure." AND THAT WAS FALSE.
  //
  // No branch of this codebase puts a FILE in a report annexure: `report_annexures` is transcripts
  // only (an AUDIO field resolved to `MediaFile.transcriptText`), and `_render_media_annexure`
  // gathers through the image path, which admits IMAGE and IMAGE_LIST alone — `report_templates`
  // records that refusal as a DELIBERATE decision with its reasons written out. What a report
  // actually does is NAME the attachment — `format_value`'s media branch prints "1 document
  // attached" under the field's own label — and then `build_report` warns, beside the download, that
  // the bytes are not inside the file.
  //
  // Three surfaces carried this same promise: here, the registry's `designerCv` help, and the
  // Android profile screen. All three now say the true thing, which is also the more useful thing:
  // it tells the designer to send the file alongside the report.
  "One document — PDF, .docx or .odt. A PDF is shown on this page as soon as it uploads; other formats are stored and downloadable. Your reports NAME it rather than carrying it, so send the file alongside the report.",
  empanelmentDate: "The date on the empanelment order, if the order carries one."
};

/**
 * The eight groups' stable keys.
 *
 * A literal union rather than a free string, and the editor types its map of controls as
 * `Record<DesignerProfileGroupKey, ReactNode>` — so adding a group here without giving it any
 * controls is a COMPILE error rather than a section that renders its heading and nothing under it.
 * A form that silently draws an empty box is the same defect class as a list that quietly stops.
 */
export const DESIGNER_PROFILE_GROUP_KEYS = [
  "identity",
  "institution",
  "qualifications",
  "biography",
  "contact",
  "address",
  "empanelment",
  "images"
] as const;

export type DesignerProfileGroupKey = (typeof DESIGNER_PROFILE_GROUP_KEYS)[number];

export type DesignerProfileGroup = {
  key: DesignerProfileGroupKey;
  title: string;
  /** Why this group is asked for, where that is not obvious from its fields. */
  blurb?: string;
  fields: DesignerProfileField[];
};

/**
 * The eight groups, in the order both screens draw them — roughly the order these values appear in
 * a finished report: who you are, where you work, what you are qualified in, the paragraph about
 * you, how to reach you, where you are, the empanelment identifiers a government report carries,
 * and finally the files you attach — photograph, signature and CV.
 */
export const DESIGNER_PROFILE_GROUPS: DesignerProfileGroup[] = [
  { key: "identity", title: "Name and standing", fields: ["displayName", "localName", "designation"] },
  { key: "institution", title: "Institution", fields: ["institution", "department"] },
  { key: "qualifications", title: "Qualifications", fields: ["qualification", "specialisation", "experienceYears"] },
  {
    key: "biography",
    title: "Designer’s profile",
    blurb: "The paragraph a report prints about the designer who ran the workshop.",
    fields: ["biography"]
  },
  { key: "contact", title: "Contact", fields: ["phone", "email", "website"] },
  { key: "address", title: "Address", fields: ["addressLine", "city", "state", "pincode"] },
  {
    key: "empanelment",
    title: "Empanelment",
    blurb: "The identifiers a government report is expected to carry. Not the same thing as the roster row that lets you sign in.",
    fields: ["empanelmentNo", "empanelmentDate"]
  },
  // THE CV JOINS THE IMAGES GROUP RATHER THAN GETTING ONE OF ITS OWN, and the group is retitled to
  // say so. `DesignerProfileGroupKey` is a literal union the editor types its control map against,
  // so a ninth group would be a compile error until controls were written for it — which is the
  // guard working as intended, but the real argument is the reader's: these three are "the files you
  // attach", they are drawn one under another by the same code path, and a section holding a single
  // upload slot below a section holding two would read as an afterthought rather than a category.
  {
    key: "images",
    title: "Photograph, signature and CV",
    fields: ["photoMediaId", "signatureMediaId", "cvMediaId"]
  }
];

/**
 * The one sentence that has to appear on both screens, because getting it wrong is a support
 * ticket: editing the profile does not touch a workshop that already exists.
 */
export const DESIGNER_PROFILE_COPY_NOTICE =
  "These details are copied into a design workshop’s stages when the workshop is created, and never read again after that. " +
  "Correcting something here changes the reports of workshops you start from now on — a workshop already under way keeps " +
  "what it was created with, because a report is a record of who ran a workshop at the time and not of who they are today. " +
  "Edit the stage itself to change an existing one.";
