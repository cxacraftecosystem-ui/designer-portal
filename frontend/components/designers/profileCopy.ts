/**
 * The words the designer profile is asked and read back in — declared ONCE for the editor and the
 * read-only view alike.
 *
 * The two screens render the same twenty columns in the same eight groups, and they must call them
 * the same thing: an admin reading a colleague's profile and that colleague editing it are looking
 * at one record, and a label that drifted between the two would make a filled field look like a
 * different, empty one. `lib/designers.DESIGNER_PROFILE_FIELDS` owns the field list; this file owns
 * what each of them is called on screen.
 *
 * FOUR LABELS ARE NOT FREE CHOICES. `displayName`, `institution`, `biography` and `experienceYears`
 * are COPIED into a workshop's stage entries when the workshop is created
 * (`prefill_from_profile`), so they end up printed under the registry's own labels — "Designer",
 * "Designer’s institution", "Designer’s profile" and "Designer’s experience", curly apostrophes and
 * all, exactly as `stage_definitions.py` declares them. Two of those read naturally as the label of
 * a box on this page and are used verbatim; the other two would be odd here ("Designer" as the
 * label of a name box on the designer's own profile), so those two are named plainly and their
 * HELP text names the registry field the value lands in. Either way a designer can trace the value
 * on their report back to the box it came from, which is the whole requirement.
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
  empanelmentNo: "Empanelment number",
  empanelmentDate: "Empanelment date"
};

/** The sentence under a box: what it is for, and where the value ends up. */
export const DESIGNER_PROFILE_HELP: Partial<Record<DesignerProfileField, string>> = {
  displayName:
    "Printed as “Designer” on the cover of every report you generate. Left blank, the cover falls back to the name on your account.",
  localName: "Printed verbatim, in whatever script you type it in.",
  institution: "Printed as “Designer’s institution” on the report cover.",
  experienceYears: "Whole years, 0 to 70 — the same range the report’s own field accepts.",
  biography: "The paragraph that appears as “Designer’s profile” in stage 3 of every report.",
  pincode: "Six digits. Optional, and never guessed for you.",
  photoMediaId: "One photograph. Attaching another replaces it.",
  signatureMediaId: "One image of your signature, for the report’s signature block. Attaching another replaces it.",
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
 * and finally the two images.
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
  { key: "images", title: "Photograph and signature", fields: ["photoMediaId", "signatureMediaId"] }
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
