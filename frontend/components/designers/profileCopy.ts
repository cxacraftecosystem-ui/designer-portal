import type { DesignerProfileField } from "@/lib/designers";

export const DESIGNER_PROFILE_LABELS: Record<DesignerProfileField, string> = {
  displayName: "Name",
  localName: "Name in the local script",
  designation: "Designation",
  institution: "Institution",
  department: "Department",
  qualification: "Qualification",
  specialisation: "Specialisation",
  experienceYears: "Designer’s experience",
  experienceMonths: "Months",
  biography: "Designer’s profile",
  phone: "Phone",
  email: "Email",
  website: "Website",
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

export const DESIGNER_EXPERIENCE_BOXES = {
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

export const DESIGNER_PROFILE_REQUIRED_FIELDS: readonly DesignerProfileField[] = [
  "displayName",
  "qualification",
  "phone",
  "email"
];

export function isDesignerProfileFieldRequired(field: DesignerProfileField): boolean {
  return DESIGNER_PROFILE_REQUIRED_FIELDS.includes(field);
}

export const DESIGNER_PROFILE_HELP: Partial<Record<DesignerProfileField, string>> = {
  displayName: "Printed as “Designer” on report covers.",
  localName: "Printed as typed.",
  institution: "Printed on report covers.",
  experienceYears:
    "Leave “Not recorded” rather than 0. Reports print the years.",
  biography: "Printed as “Designer’s profile” in reports.",
  addressLine:
    "Printed on one line on report covers.",
  pincode: "Six digits. Optional.",
  photoMediaId: "One photograph. Attaching another replaces it.",
  signatureMediaId:
    "One image. Prints with the report’s photographs.",
  cvMediaId:
  "PDF, .docx or .odt. Reports name it but don’t include it — send it with the report.",
  empanelmentDate: "As on the empanelment order."
};

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
  blurb?: string;
  fields: DesignerProfileField[];
};

export const DESIGNER_PROFILE_GROUPS: DesignerProfileGroup[] = [
  { key: "identity", title: "Name and standing", fields: ["displayName", "localName", "designation"] },
  { key: "institution", title: "Institution", fields: ["institution", "department"] },
  {
    key: "qualifications",
    title: "Qualifications",
    fields: ["qualification", "specialisation", "experienceYears", "experienceMonths"]
  },
  {
    key: "biography",
    title: "Designer’s profile",
    blurb: "Printed in reports about you.",
    fields: ["biography"]
  },
  { key: "contact", title: "Contact", fields: ["phone", "email", "website"] },
  { key: "address", title: "Postal address", fields: ["addressLine", "city", "state", "pincode"] },
  {
    key: "empanelment",
    title: "Empanelment",
    blurb: "Identifiers for government reports.",
    fields: ["empanelmentNo", "empanelmentDate"]
  },
  {
    key: "images",
    title: "Photograph, signature and CV",
    fields: ["photoMediaId", "signatureMediaId", "cvMediaId"]
  }
];

export const DESIGNER_PROFILE_COPY_NOTICE =
  "Copied into new workshops. Existing workshops keep their details — edit those on the stage.";
