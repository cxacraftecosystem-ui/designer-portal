import { apiFetch, buildQuery } from "@/lib/api";
import { writeBody } from "@/lib/writeBody";
import type { LocationPayload, PageResult, UserRole } from "@/lib/types";

export const DESIGNER_PROFILE_MEDIA_RECORD_TYPE = "designerProfile";

export type DesignerRosterEntry = {
  id: string;
  email: string;
  fullName: string | null;
  institution: string | null;
  notes: string | null;
  isActive: boolean;
  revokedAt: string | null;
  firstSeenAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  addedById: string | null;
  addedByName?: string | null;
};

export type DesignerRosterCreateBody = {
  email: string;
  fullName?: string | null;
  institution?: string | null;
  notes?: string | null;
  isActive?: boolean;
};

export type DesignerRosterUpdateBody = {
  email?: string;
  fullName?: string | null;
  institution?: string | null;
  notes?: string | null;
  isActive?: boolean;
};

export type DesignerRosterListParams = {
  page?: number;
  pageSize?: number;
  search?: string | null;
  activeOnly?: boolean;
};

export function listDesignerRoster(params: DesignerRosterListParams = {}) {
  return apiFetch<PageResult<DesignerRosterEntry>>(
    `/designers/roster${buildQuery({
      page: params.page,
      pageSize: params.pageSize,
      search: params.search ?? undefined,
      activeOnly: params.activeOnly ? "true" : undefined
    })}`
  );
}

export function designerRosterCreateBody(body: DesignerRosterCreateBody) {
  return writeBody(body, "POST");
}

export function addDesignerToRoster(body: DesignerRosterCreateBody) {
  return apiFetch<DesignerRosterEntry>("/designers/roster", {
    method: "POST",
    body: JSON.stringify(designerRosterCreateBody(body))
  });
}

export function updateDesignerRosterEntry(rosterId: string, body: DesignerRosterUpdateBody) {
  return apiFetch<DesignerRosterEntry>(`/designers/roster/${rosterId}`, {
    method: "PATCH",
    body: JSON.stringify(body)
  });
}

export function suspendDesignerRosterEntry(rosterId: string) {
  return apiFetch<DesignerRosterEntry>(`/designers/roster/${rosterId}`, { method: "DELETE" });
}

export function restoreDesignerRosterEntry(rosterId: string) {
  return updateDesignerRosterEntry(rosterId, { isActive: true });
}

export function rosterUpdateBody(values: {
  email?: string | null;
  fullName?: string | null;
  institution?: string | null;
  notes?: string | null;
  isActive?: boolean | null;
}): DesignerRosterUpdateBody {
  const body: DesignerRosterUpdateBody = {
    fullName: blankToNull(values.fullName),
    institution: blankToNull(values.institution),
    notes: blankToNull(values.notes)
  };
  const email = (values.email ?? "").trim();
  if (email) body.email = email;
  if (values.isActive !== undefined && values.isActive !== null) body.isActive = values.isActive;
  return body;
}

export function rosterInvitationLabel(entry: Pick<DesignerRosterEntry, "firstSeenAt">): string {
  return entry.firstSeenAt ? "Signed in" : "Invitation outstanding";
}

export type DesignerDirectoryEntry = {
  id: string;
  name: string;
  email: string;
  role: UserRole | string;
  institution: string | null;
  rosterId: string | null;
  rosterActive: boolean;
  canSignIn: boolean;
  firstSeenAt: string | null;
  hasProfile: boolean;
};

export function listDesignerDirectory(params: { search?: string | null; includeSuspended?: boolean } = {}) {
  return apiFetch<DesignerDirectoryEntry[]>(
    `/designers/directory${buildQuery({
      search: params.search ?? undefined,
      includeSuspended: params.includeSuspended ? "true" : undefined
    })}`
  );
}

export const DESIGNER_PROFILE_FIELDS = [
  "displayName",
  "localName",
  "designation",
  "institution",
  "department",
  "qualification",
  "specialisation",
  "experienceYears",
  "experienceMonths",
  "biography",
  "phone",
  "email",
  "website",
  "addressLine",
  "city",
  "state",
  "pincode",
  "photoMediaId",
  "signatureMediaId",
  "cvMediaId",
  "empanelmentNo",
  "empanelmentDate"
] as const;

export type DesignerProfileField = (typeof DESIGNER_PROFILE_FIELDS)[number];

export type DesignerProfile = {
  id: string;
  userId: string;
  displayName: string | null;
  localName: string | null;
  designation: string | null;
  institution: string | null;
  department: string | null;
  qualification: string | null;
  specialisation: string | null;
  experienceYears: number | null;
  experienceMonths: number | null;
  biography: string | null;
  phone: string | null;
  phoneCountryCode?: string | null;
  email: string | null;
  website: string | null;
  addressLine: string | null;
  city: string | null;
  state: string | null;
  pincode: string | null;
  photoMediaId: string | null;
  signatureMediaId: string | null;
  cvMediaId: string | null;
  empanelmentNo: string | null;
  empanelmentDate: string | null;
  empanelmentNoMissing?: boolean;
  signInByPhone?: boolean;
  signInByEmpanelmentNo?: boolean;
  locationId: string | null;
  location: DesignerProfileLocation | null;
  createdAt: string | null;
  updatedAt: string | null;
};

export type DesignerProfileLocation = {
  id: string;
  latitude: number | null;
  longitude: number | null;
  altitude: number | null;
  accuracy: number | null;
  capturedAt: string | null;
  placeName: string | null;
  address: string | null;
  state: string | null;
  district: string | null;
  village: string | null;
  pincode: string | null;
  subjectLatitude: number | null;
  subjectLongitude: number | null;
};

export type DesignerProfileUpdateBody = {
  displayName: string | null;
  localName: string | null;
  designation: string | null;
  institution: string | null;
  department: string | null;
  qualification: string | null;
  specialisation: string | null;
  experienceYears: number | null;
  experienceMonths: number | null;
  biography: string | null;
  phone: string | null;
  phoneCountryCode?: string | null;
  email: string | null;
  website: string | null;
  addressLine: string | null;
  city: string | null;
  state: string | null;
  pincode: string | null;
  photoMediaId: string | null;
  signatureMediaId: string | null;
  cvMediaId: string | null;
  empanelmentNo: string | null;
  empanelmentDate: string | null;
  location?: LocationPayload;
};

export type DesignerProfileDraft = Partial<Record<DesignerProfileField, string | number | null | undefined>> & {
  location?: LocationPayload;
};

function blankToNull(value: string | number | null | undefined): string | null {
  if (value === null || value === undefined) return null;
  const trimmed = String(value).trim();
  return trimmed ? trimmed : null;
}

function wholeNumberOrNull(value: string | number | null | undefined): number | null {
  if (value === null || value === undefined) return null;
  const text = String(value).trim();
  if (text === "") return null;
  const parsed = Number(text);
  return Number.isFinite(parsed) ? Math.trunc(parsed) : null;
}

export function fullDesignerProfileBody(draft: DesignerProfileDraft): DesignerProfileUpdateBody {
  return {
    displayName: blankToNull(draft.displayName),
    localName: blankToNull(draft.localName),
    designation: blankToNull(draft.designation),
    institution: blankToNull(draft.institution),
    department: blankToNull(draft.department),
    qualification: blankToNull(draft.qualification),
    specialisation: blankToNull(draft.specialisation),
    experienceYears: wholeNumberOrNull(draft.experienceYears),
    experienceMonths: wholeNumberOrNull(draft.experienceMonths),
    biography: blankToNull(draft.biography),
    phone: blankToNull(draft.phone),
    email: blankToNull(draft.email),
    website: blankToNull(draft.website),
    addressLine: blankToNull(draft.addressLine),
    city: blankToNull(draft.city),
    state: blankToNull(draft.state),
    pincode: blankToNull(draft.pincode),
    photoMediaId: blankToNull(draft.photoMediaId),
    signatureMediaId: blankToNull(draft.signatureMediaId),
    cvMediaId: blankToNull(draft.cvMediaId),
    empanelmentNo: blankToNull(draft.empanelmentNo),
    empanelmentDate: blankToNull(String(draft.empanelmentDate ?? "").slice(0, 10)),
    ...(draft.location ? { location: draft.location } : {})
  };
}

export function getMyDesignerProfile() {
  return apiFetch<DesignerProfile>("/designers/me/profile");
}

export function saveMyDesignerProfile(body: DesignerProfileUpdateBody) {
  return apiFetch<DesignerProfile>("/designers/me/profile", { method: "PUT", body: JSON.stringify(body) });
}

export function getDesignerProfile(userId: string) {
  return apiFetch<DesignerProfile>(`/designers/${userId}/profile`);
}

export function saveDesignerProfile(userId: string, body: DesignerProfileUpdateBody) {
  return apiFetch<DesignerProfile>(`/designers/${userId}/profile`, { method: "PUT", body: JSON.stringify(body) });
}

export function designerProfileIsEmpty(profile: DesignerProfile | null | undefined): boolean {
  if (!profile) return true;
  if (profile.location) return false;
  return DESIGNER_PROFILE_FIELDS.every((field) => {
    const value = profile[field];
    return value === null || value === undefined || (typeof value === "string" && !value.trim());
  });
}
