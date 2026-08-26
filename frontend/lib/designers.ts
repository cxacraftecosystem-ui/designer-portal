/**
 * The designer roster and the designer profile — two tables, two shapes, and NOT the same fact.
 *
 * `DesignerRoster` is the ADMIN's statement that an individual is empanelled and may sign in at all.
 * `DesignerProfile` is the DESIGNER's own statement of who they are, and it is the text a report
 * prints under their name. They never share a field here even where they both say "institution",
 * because collapsing them is how a screen ends up letting an admin rewrite somebody's biography, or
 * letting a designer edit their own permission to log in. The backend keeps them in two routers, two
 * schemas and two tables for exactly that reason (backend/app/services/designers.py); this file
 * keeps them in two type families for the same one.
 *
 * FOUR THINGS ON THIS WIRE WILL TRIP A READER WHO ASSUMES THE REST OF THE REPOSITORY'S CONVENTIONS.
 *
 * 1. **`DELETE /designers/roster/{id}` answers 200 WITH THE ROW, not 204 with nothing.** It is a
 *    suspension, never a deletion: `isActive` goes false and `revokedAt` is stamped, and the row
 *    stays because the roster is the RECORD that somebody was empanelled — an audit two years later
 *    asks who was recognised under which programme, and a deleted row answers "nobody". So
 *    {@link suspendDesignerRosterEntry} returns the suspended entry and callers must render it
 *    rather than removing the row from the list.
 *
 * 2. **Restoring is a PATCH, not an un-DELETE**, because "give this person their access back" is not
 *    a thing a DELETE can express. {@link restoreDesignerRosterEntry} exists so no call site has to
 *    remember that the two halves of one toggle are two different verbs.
 *
 * 3. **A profile PUT is applied with `exclude_unset`: an absent key keeps the stored value and a
 *    key present and null CLEARS it.** That distinction is load-bearing and it is the reason
 *    {@link fullDesignerProfileBody} exists. A form that omitted its empty boxes would watch a
 *    deleted department reappear on the next load, every time, with no error and nothing on screen
 *    to blame; a PARTIAL editor that reused the full encoder would erase every column it does not
 *    render. Android encodes the identical body by hand for the identical reason — see
 *    `designerProfileUpdateJson` in android/.../data/ApiModels.kt — and the two must stay in step.
 *
 * 4. **`photoMediaId` and `signatureMediaId` are MEDIA IDS, never URLs.** They are resolved through
 *    `GET /media/{id}` exactly as a stage field's photograph is. A URL stored in one of these
 *    columns would be a pre-signed link that expires, so a report generated three months later
 *    prints a broken image and nothing in the stored row says why.
 */

import { apiFetch, buildQuery } from "@/lib/api";
import type { PageResult, UserRole } from "@/lib/types";

/* ────────────────────────────────────────────────────────────────────────────
 * Media
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The `linkedRecordType` a designer's photograph and signature are uploaded under.
 *
 * A single literal, shared by every caller, because `GET /media` filters on this exact string: a
 * second spelling would upload the photograph successfully and then be unable to find it again,
 * which reads to the designer as a file that vanished. It is a STRING TAG with no typed foreign key
 * on `MediaFile` — the same arrangement as `designWorkshop` — so it is deliberately absent from the
 * backend's `ORPHAN_FK_FIELDS` and a profile photo can never surface on the admin hub's "recovered
 * recordings" table as though its parent record had been deleted.
 */
export const DESIGNER_PROFILE_MEDIA_RECORD_TYPE = "designerProfile";

/* ────────────────────────────────────────────────────────────────────────────
 * The roster
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One row of the institution's designer roster, as `GET /designers/roster` serves it.
 *
 * THE ROW USUALLY EXISTS BEFORE THE ACCOUNT DOES. That is the whole point of the table: an admin
 * empanels somebody by email address, and the account provisions itself the first time that person
 * signs in through Google. So there is no user id here, `fullName` is whatever the admin typed
 * rather than anything an account has confirmed, and `firstSeenAt` is null for exactly as long as
 * the invitation is outstanding.
 *
 * **A null `firstSeenAt` must never be rendered as "never" or as an error.** It means "has not
 * accepted yet", which is the one fact the roster screen exists to show — an invitation that never
 * arrived looks exactly like one that was ignored, and this is the only column that tells them
 * apart. {@link rosterInvitationLabel} is the shared sentence for it.
 */
export type DesignerRosterEntry = {
  id: string;
  /** Lower-cased server-side on write. The join key to `User.email`. */
  email: string;
  fullName: string | null;
  institution: string | null;
  notes: string | null;
  /** False suspends sign-in WITHOUT deleting the history of the empanelment. */
  isActive: boolean;
  revokedAt: string | null;
  /** Set the first time an account with this email signs in. Null = the invitation is outstanding. */
  firstSeenAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  addedById: string | null;
  /**
   * Who empanelled them, when the server joins it in. `roster_payload` sends only `addedById`
   * today, so this is optional rather than nullable-required: a screen must be able to say nothing
   * about the adding admin instead of printing an empty cell that looks like a lost name.
   */
  addedByName?: string | null;
};

/**
 * `POST /designers/roster` — empanel somebody.
 *
 * The email is the only required fact and the only one that matters; everything else is the admin's
 * note to themselves about whom they added and why. `fullName` is what the roster screen shows
 * while the invitation is outstanding, because until the person signs in there is no account to
 * read a name from.
 */
export type DesignerRosterCreateBody = {
  email: string;
  fullName?: string | null;
  institution?: string | null;
  notes?: string | null;
  isActive?: boolean;
};

/**
 * `PATCH /designers/roster/{id}` — a correction, or a restore.
 *
 * `email` is OPTIONAL IN A WAY THE OTHER THREE ARE NOT, and the asymmetry is deliberate rather than
 * an oversight: it is the row's unique join key to `User.email` and the server applies the body with
 * `exclude_unset`, so a present-and-null `email` would read as "clear the address" — on the one
 * column that decides whether a person can sign in. {@link rosterUpdateBody} is what enforces that;
 * do not hand-build this type at a call site.
 */
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
  /**
   * Hide suspended rows. Off by default, and that default is the point of the screen: an admin
   * looking for a designer who says they cannot log in needs to SEE the row that is refusing them.
   * A list that hid it would leave them re-adding an email the unique index then rejects, with no
   * explanation visible anywhere in the UI.
   */
  activeOnly?: boolean;
};

/**
 * The roster, newest first, suspended rows included unless `activeOnly` is asked for.
 *
 * `buildQuery` takes no booleans and drops "" exactly as it drops null, so `activeOnly` is spelled
 * out as the literal "true" and omitted entirely when it is off.
 */
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

/**
 * Empanel an email address. No user account is required and none is created.
 *
 * A duplicate answers **409 naming the existing row** — including whether it is suspended and the id
 * to restore — rather than overwriting it, because the usual way to arrive here is an admin
 * re-empanelling somebody they suspended in March, and an overwrite would erase the note recording
 * the original empanelment. That sentence is the whole answer to "why can I not add this person",
 * so a caller must surface `ApiError.message` verbatim and must not replace it with wording of its
 * own.
 */
export function addDesignerToRoster(body: DesignerRosterCreateBody) {
  return apiFetch<DesignerRosterEntry>("/designers/roster", {
    method: "POST",
    body: JSON.stringify(body)
  });
}

export function updateDesignerRosterEntry(rosterId: string, body: DesignerRosterUpdateBody) {
  return apiFetch<DesignerRosterEntry>(`/designers/roster/${rosterId}`, {
    method: "PATCH",
    body: JSON.stringify(body)
  });
}

/**
 * Suspend a roster entry — SUSPEND, never delete. Answers 200 with the suspended row.
 *
 * Idempotent on the server: suspending an already-suspended row keeps the ORIGINAL `revokedAt`,
 * because that date is the answer to "when did this designer lose access" and a second click would
 * otherwise move it to today and destroy it.
 */
export function suspendDesignerRosterEntry(rosterId: string) {
  return apiFetch<DesignerRosterEntry>(`/designers/roster/${rosterId}`, { method: "DELETE" });
}

/**
 * Give a suspended designer their access back. A PATCH, because a DELETE cannot express a restore.
 *
 * The server CLEARS `revokedAt` as it does so: a row that was active and still carried a revocation
 * date would leave the next admin reading two facts that disagree, with no way to tell whether the
 * person may sign in.
 */
export function restoreDesignerRosterEntry(rosterId: string) {
  return updateDesignerRosterEntry(rosterId, { isActive: true });
}

/**
 * The `PATCH` body for a roster correction, built key by key.
 *
 * `email` travels only when it is non-blank — see {@link DesignerRosterUpdateBody}. The three
 * descriptive columns DO carry an explicit null when their box is emptied, because clearing the
 * "Notes" box has to be able to mean clearing the notes. `isActive` travels only when a suspension
 * or a restore is actually being asked for, so an ordinary correction can never flip somebody's
 * access as a side effect of fixing a spelling.
 *
 * Character-for-character the rule Android's `designerRosterUpdateJson` follows; change one and
 * change the other.
 */
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

/**
 * Whether the invitation has been taken up, as a sentence.
 *
 * Shared so the roster table, the directory picker and anything else that grows later cannot each
 * invent their own wording for the same column — and so that "not yet" can never be rendered as
 * "never", which is a statement about a person rather than about an invitation.
 */
export function rosterInvitationLabel(entry: Pick<DesignerRosterEntry, "firstSeenAt">): string {
  return entry.firstSeenAt ? "Signed in" : "Invitation outstanding";
}

/* ────────────────────────────────────────────────────────────────────────────
 * The directory — who an admin may hand a workshop to
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One row of `GET /designers/directory`: an account that may run a design workshop, with its
 * standing on the roster attached.
 *
 * `canSignIn` and `rosterActive` deliberately differ. `rosterActive` is a fact about a table row;
 * `canSignIn` is the answer to the question a picker is actually asking, and for a professor or an
 * admin — who can run a workshop and whom the roster does not gate at all — there is no roster row
 * and the two are not the same. **Disable a row on `canSignIn`.**
 */
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

/**
 * The accounts a workshop may be handed to. Admin only, like the roster it reads.
 *
 * Suspended designers are HIDDEN by default, and that is the useful part: assigning a fortnight of
 * fieldwork to somebody whose roster row was revoked last month produces a workshop nobody can open
 * — the assignment succeeds, their next sign-in is refused, and the gap is discovered when the
 * report is due. `includeSuspended` shows them, marked, for an admin deciding whom to restore.
 */
export function listDesignerDirectory(params: { search?: string | null; includeSuspended?: boolean } = {}) {
  return apiFetch<DesignerDirectoryEntry[]>(
    `/designers/directory${buildQuery({
      search: params.search ?? undefined,
      includeSuspended: params.includeSuspended ? "true" : undefined
    })}`
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * The profile
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Every column of `DesignerProfile` a person may write — which is all of them but the identifiers
 * and the timestamps — in the order the form and the read-only view both render them.
 *
 * NAMED ONCE, HERE, and consumed by the encoder, the form and the display alike. The backend names
 * the same twenty-one in `PROFILE_FIELDS` for the same reason and says it plainly: three copies of a
 * twenty-one-name list is two copies that will disagree, and the way that failure surfaces is a field
 * the designer can save and then cannot see.
 */
export const DESIGNER_PROFILE_FIELDS = [
  "displayName",
  "localName",
  "designation",
  "institution",
  "department",
  "qualification",
  "specialisation",
  "experienceYears",
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
  // The CV, added 2026-08-25. A media id like the two above it and never a URL — rule 4 in this
  // file's header. It is the one profile column whose file is usually NOT an image, which is why
  // the form gives it a document slot and `DocumentPreview` rather than `StoredMediaImage`.
  "cvMediaId",
  "empanelmentNo",
  "empanelmentDate"
] as const;

export type DesignerProfileField = (typeof DESIGNER_PROFILE_FIELDS)[number];

/**
 * The signed-in account's profile as `GET /designers/me/profile` serves it.
 *
 * The row is created empty on first read (the server upserts rather than 404s), so every value here
 * is legitimately null for somebody who has never opened the screen — an empty profile and a missing
 * one are the same thing and no caller has to tell them apart.
 */
export type DesignerProfile = {
  id: string;
  userId: string;
  displayName: string | null;
  /** Written in the local script where the designer wants it that way; printed verbatim. */
  localName: string | null;
  designation: string | null;
  institution: string | null;
  department: string | null;
  qualification: string | null;
  specialisation: string | null;
  experienceYears: number | null;
  /** The paragraph that appears as "Designer's profile" in stage 3 of every report. */
  biography: string | null;
  phone: string | null;
  email: string | null;
  website: string | null;
  addressLine: string | null;
  city: string | null;
  state: string | null;
  pincode: string | null;
  /** A media id, resolved through `GET /media/{id}`. Never a URL — see the file header. */
  photoMediaId: string | null;
  signatureMediaId: string | null;
  /** The designer's CV. A media id; rendered inline where the stored mime type is a PDF. */
  cvMediaId: string | null;
  empanelmentNo: string | null;
  /** ISO-8601, and a STRING rather than a date, matching every other date this API accepts. */
  empanelmentDate: string | null;
  createdAt: string | null;
  updatedAt: string | null;
};

/**
 * The body of `PUT /designers/{…}/profile` with EVERY key present — an explicit null being what
 * says "clear this column".
 *
 * Every key is REQUIRED in the type rather than optional, deliberately: it is the type system's way
 * of saying that this body is only correct for an editor that renders all twenty-one fields. A future
 * partial editor must declare its own narrower body rather than reach for this one.
 */
export type DesignerProfileUpdateBody = {
  displayName: string | null;
  localName: string | null;
  designation: string | null;
  institution: string | null;
  department: string | null;
  qualification: string | null;
  specialisation: string | null;
  experienceYears: number | null;
  biography: string | null;
  phone: string | null;
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
};

/**
 * What a form hands the encoder: every value as the string a control produced, or a number, or
 * null. `FormData` yields strings for everything including the year count, so the encoder — not
 * every call site — is where a string becomes the integer the column wants.
 */
export type DesignerProfileDraft = Partial<Record<DesignerProfileField, string | number | null | undefined>>;

/**
 * A text box's contents as the API should read them: the trimmed string, or null when it is empty.
 *
 * The blank-to-null fold happens on the CLIENT and not only on the server because `""` and `null`
 * are different instructions to a nullable column, and the difference shows up in the printed
 * report: an empty string satisfies "is this column set?" while carrying nothing to typeset, so a
 * cover page ends up with a designation line that is present, blank, and impossible to tell apart
 * from a layout bug.
 */
function blankToNull(value: string | number | null | undefined): string | null {
  if (value === null || value === undefined) return null;
  const trimmed = String(value).trim();
  return trimmed ? trimmed : null;
}

/**
 * A draft as the full twenty-one-key wire body, with cleared fields carrying an explicit null.
 *
 * `experienceYears` is parsed here and NOT clamped here. The column is bounded 0–70 by the server —
 * the same bounds as the registry's `designerExperience` field, which this value is copied into —
 * and a pydantic rejection 422s the WHOLE body, taking the twenty fields the designer got right
 * with it. So the form blocks it natively with `min`/`max` on the input and this function only
 * refuses what is not a number at all; silently clamping 400 to 70 would store a number nobody
 * typed and print it on a cover page as a statement about a person.
 */
export function fullDesignerProfileBody(draft: DesignerProfileDraft): DesignerProfileUpdateBody {
  const years = draft.experienceYears;
  const parsed = years === null || years === undefined || String(years).trim() === "" ? null : Number(years);
  return {
    displayName: blankToNull(draft.displayName),
    localName: blankToNull(draft.localName),
    designation: blankToNull(draft.designation),
    institution: blankToNull(draft.institution),
    department: blankToNull(draft.department),
    qualification: blankToNull(draft.qualification),
    specialisation: blankToNull(draft.specialisation),
    experienceYears: parsed !== null && Number.isFinite(parsed) ? Math.trunc(parsed) : null,
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
    // Sliced to the date part before it is sent. The column is a DateTime and the server parses
    // `str(raw)[:10]`, so a value that arrived as a full ISO timestamp (which is how the GET serves
    // it back) would be re-sent whole and re-parsed to the same day — harmless, but the control this
    // came from (`DateField`, which submits a bare `yyyy-mm-dd` through its hidden input) emits only
    // the date part, and keeping the two identical is what makes a save-with-no-edits a genuine
    // no-op rather than something that looks like a change in an audit trail.
    empanelmentDate: blankToNull(String(draft.empanelmentDate ?? "").slice(0, 10))
  };
}

/** The signed-in account's own profile. Created empty on first read; any role, deliberately. */
export function getMyDesignerProfile() {
  return apiFetch<DesignerProfile>("/designers/me/profile");
}

export function saveMyDesignerProfile(body: DesignerProfileUpdateBody) {
  return apiFetch<DesignerProfile>("/designers/me/profile", { method: "PUT", body: JSON.stringify(body) });
}

/**
 * Another account's profile — the owner or an admin, and **404 for everybody else**.
 *
 * 404 and not 403, and both cases carry the identical detail string, because a 403 would confirm
 * that the id belongs to a real account: a designer holding a list of cuids could otherwise
 * enumerate the staff by watching which ones answer which. A caller must therefore not translate a
 * 404 here into "no such designer" — it means "no such designer, or not one you may see", and
 * saying more than that on screen would leak exactly what the status code is hiding.
 */
export function getDesignerProfile(userId: string) {
  return apiFetch<DesignerProfile>(`/designers/${userId}/profile`);
}

/**
 * Write another account's profile — the owner or an admin, and NOBODY else.
 *
 * The text saved here is printed verbatim in a report submitted under that person's name, so this
 * is not a matter of tidiness: a designer able to edit a colleague's copy could put words into a
 * document that goes to a ministry over the colleague's signature. The server enforces it; a client
 * that merely hides the Save button has enforced nothing.
 */
export function saveDesignerProfile(userId: string, body: DesignerProfileUpdateBody) {
  return apiFetch<DesignerProfile>(`/designers/${userId}/profile`, { method: "PUT", body: JSON.stringify(body) });
}

/**
 * Has this profile been filled in at all?
 *
 * `photoMediaId` and `signatureMediaId` count, because a designer who has uploaded a signature and
 * nothing else has still started. The row itself never answers this — it is created empty on the
 * first GET, so "a profile exists" is true for everybody who has ever opened the screen.
 */
export function designerProfileIsEmpty(profile: DesignerProfile | null | undefined): boolean {
  if (!profile) return true;
  return DESIGNER_PROFILE_FIELDS.every((field) => {
    const value = profile[field];
    return value === null || value === undefined || (typeof value === "string" && !value.trim());
  });
}
