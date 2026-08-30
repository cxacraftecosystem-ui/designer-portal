/**
 * The words the designer profile is asked and read back in — declared ONCE for the editor and the
 * read-only view alike.
 *
 * The two screens render the same twenty-two columns in the same eight groups, and they must call
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
 * ONE COLUMN BREAKS THAT RULE, AND IT SAYS SO RATHER THAN PRETENDING OTHERWISE. `experienceMonths`
 * (2026-08-30, requirement 14) has NO receiving registry field: adding one moves `registry_version()`
 * and owes a re-dump of the bundled Android schema plus a re-cut APK, so the backend put it in
 * `PREFILL_EXEMPT` instead and a report still prints the years alone. The months save, read back and
 * appear on both of these screens — and the help text under the pair says exactly that, because a
 * box whose value silently never reaches the document it was asked for is the failure this whole
 * file exists to make impossible to ship quietly.
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
  // Verbatim from the registry (`designerExperience`), which is where this number is printed. It
  // names the PAIR as well as the column: one heading over two dropdowns in the editor, and one row
  // printing "12 years 6 months" in the read-only view. See DESIGNER_EXPERIENCE_BOXES below.
  experienceYears: "Designer’s experience",
  // The months box's OWN name, drawn under that heading and never on its own — which is why it is a
  // bare noun rather than a sentence. Two dropdowns on one line labelled only "Years" and "Months"
  // would be two orphans with no idea what they measure; the heading above them is what says.
  experienceMonths: "Months",
  // Verbatim from the registry (`designerProfile`), the stage-3 heading this paragraph appears under.
  biography: "Designer’s profile",
  phone: "Phone",
  email: "Email",
  website: "Website",
  // "Address line", not "Address", and VERBATIM FROM ANDROID (§1.3) — `ProfileText("Address line",
  // …)` in `DesignerProfileScreen.kt`, renamed there on 2026-08-30 with the reason that holds here
  // unchanged: there are now two addresses on this screen, and an unqualified word over one of two
  // is the reading a person gets wrong. It also ends the repetition a reader met first — the group
  // heading below said "Address" and the first box under it said "Address", so the page opened by
  // saying the same word twice before asking anything.
  addressLine: "Address line",
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
 * The experience pair as the two screens compose it: one heading over both boxes, each box's own
 * name under it, and a standalone accessible name for each control.
 *
 * ── WHY EACH CONTROL CARRIES AN EXPLICIT `ariaLabel` RATHER THAN INHERITING THE HEADING ──────────
 *
 * `FieldBlock` draws a `role="group"` named by its heading AND publishes that heading's id, which
 * every themed dropdown inside it composes into its own name (`ui/fieldLabel.tsx`). With ONE control
 * in the slot that is exactly right — "Craft Bamboo". With TWO it is a defect: both triggers would
 * announce "Designer’s experience 12" and "Designer’s experience 6", identical questions with
 * different answers, and a reader who tabbed back to check one could not tell which box they were
 * standing in. So each box says what it measures.
 *
 * The visible word is CONTAINED IN the spoken name ("Years" inside "Designer’s experience, in whole
 * years"), which is what keeps a voice-control user able to say what they can see.
 *
 * THE GROUP IS STILL DRAWN AND STILL NAMED. A group label is announced on ENTERING the group, so the
 * two together give a reader arriving in order "Designer’s experience … Years … Months", and a
 * reader arriving by Tab the full name of whichever box they landed on.
 */
export const DESIGNER_EXPERIENCE_BOXES = {
  /** The one heading over both boxes — the registry's own wording for the value it prints. */
  group: DESIGNER_PROFILE_LABELS.experienceYears,
  years: {
    label: "Years",
    ariaLabel: `${DESIGNER_PROFILE_LABELS.experienceYears}, in whole years`
  },
  months: {
    label: DESIGNER_PROFILE_LABELS.experienceMonths,
    ariaLabel: `${DESIGNER_PROFILE_LABELS.experienceYears}, in months`
  }
} as const;

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
  // ONE SENTENCE FOR THE PAIR, because it is one control with two boxes — `experienceMonths` is
  // deliberately given no help line of its own, or the panel would carry two paragraphs about one
  // answer. The last clause is the honest half and must not be dropped: the months are stored and
  // shown here, and the report prints the years alone, because `experienceMonths` has no receiving
  // registry field yet (see this file's header). Promising a report line the document will not
  // contain is the mistake `cvMediaId` and `signatureMediaId` below each had to have corrected.
  experienceYears:
    "Years 0 to 70 and months 0 to 11, chosen separately — leave a box on “Not recorded” rather than answering 0, which means something different. The years are the range the report’s own field accepts; the months are kept on your profile and are not printed on a report yet.",
  biography: "The paragraph that appears as “Designer’s profile” in stage 3 of every report.",
  // ── ADDED 2026-08-30, WITH THE EDITOR, AND EVERY CLAUSE OF IT IS CHECKED CODE ──────────────────
  //
  // §17 ("claims about the report — copy written from copy") is the reason this sentence is short
  // and specific rather than reassuring. It is NOT written from Android's copy or from the
  // walkthrough: the whole of it is `prefill_from_profile` in `backend/app/services/designers.py`,
  // which copies this column into the registry's `designerAddress` through
  // `rich_text.plain_from_stored` and then `_fold_to_one_line`. So the words reach the document, the
  // marks and the line breaks do not, and `test_a_multi_paragraph_address_reaches_the_report_cover_
  // as_one_line` is what keeps that true.
  //
  // WHY SAY IT AT ALL. A formatting toolbar is a promise, and a designer who bolds their street and
  // finds it unbolded on a submitted cover has been misled by a control rather than by a sentence.
  // The handset says the same thing from the other side — that editing a formatted address there
  // replaces it — so the two clients describe one behaviour rather than each describing its half.
  addressLine:
    "Printed on the report cover as plain text, on one line. Formatting and line breaks are kept on your profile and are not carried into the document.",
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
  // `experienceMonths` sits beside `experienceYears` and is drawn WITH it, not under it — one
  // heading, two dropdowns on one line in the editor, one row in the view. It is listed here so the
  // record stays complete (every writable column belongs to exactly one group, which is what lets a
  // reader check the two screens against each other), not because it gets a box of its own.
  {
    key: "qualifications",
    title: "Qualifications",
    fields: ["qualification", "specialisation", "experienceYears", "experienceMonths"]
  },
  {
    key: "biography",
    title: "Designer’s profile",
    blurb: "The paragraph a report prints about the designer who ran the workshop.",
    fields: ["biography"]
  },
  { key: "contact", title: "Contact", fields: ["phone", "email", "website"] },
  // "Postal address", VERBATIM FROM ANDROID's `ProfileSection("Postal address")` (§1.3), which was
  // renamed there on 2026-08-30 saying why: "because there are now two of them and an unqualified
  // heading over one of two is the reading a person gets wrong". These four are the columns the
  // report prefill copies; the `LocationFields` card the editor draws under them holds the district
  // and the map point, which no column here can hold. The web was out of parity by these two strings
  // for a day — this one and `addressLine`'s label above.
  { key: "address", title: "Postal address", fields: ["addressLine", "city", "state", "pincode"] },
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
