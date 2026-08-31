/**
 * The eight tiers, highest first. THE ROW THAT MATTERS MOST in the whole mirror set: every
 * `Record<UserRole, …>` in this client is exhaustive against THIS union and nothing else, so a tier
 * missing here silently un-enforces four self-enforcing mirrors at once — `tsc` keeps passing, the
 * records stay "complete", and all of them are complete against the wrong ladder.
 * `backend/tests/test_role_ladder_parity.py` is what compares it to the server; nothing else does.
 *
 * `INSPECTOR` (rank 37) is stored as INSPECTOR and LABELLED "Inspector / Reviewer" — see
 * `lib/permissions.ts::ROLE_LABELS`. "Reviewer" is not available as a token because `canReview`
 * already means something relational and different here.
 */
export type UserRole =
  | "MASTER_ADMIN"
  | "ADMIN"
  | "PROFESSOR"
  | "INSPECTOR"
  | "DESIGNER"
  | "RESEARCHER"
  | "FIELD_CONTRIBUTOR"
  | "CROWDSOURCE_VOLUNTEER";
export type RecordStatus = "DRAFT" | "PENDING" | "APPROVED" | "REJECTED" | "NEEDS_REVISION";
export type MediaType = "IMAGE" | "VIDEO" | "AUDIO" | "PDF" | "DOCUMENT" | "OTHER";

export type PageResult<T> = {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
  pages: number;
};

export type User = {
  id: string;
  email: string;
  name: string;
  role: UserRole;
  avatarUrl?: string | null;
  authProvider?: string;
  canManageQuestionnaire?: boolean;
  canManageCrafts?: boolean;
  canManageWorkshops?: boolean;
  canReview?: boolean;
  canViewProvenance?: boolean;
  canDownloadDataset?: boolean;
  /**
   * ── THE FIRST-LOGIN PASSWORD, 2026-08-30 ────────────────────────────────────────────────────
   *
   * True when the password on this account was typed by an ADMINISTRATOR rather than chosen by its
   * owner — `POST /api/users`, or a password written through `PATCH /api/users/{id}` for somebody
   * else. It arrives on every `/me` and on the sign-in response for free, because `serialize_user`
   * encodes the whole row.
   *
   * **`/login` IS THE SCREEN THAT CONSUMES IT, SINCE 2026-08-31.** This paragraph used to say that
   * no screen on either client did, and it was right: the flag rode on every payload and nobody was
   * ever asked anything, so an admin-issued password — a secret two people know by construction —
   * stayed on the account for as long as its owner cared to keep using it. `FirstPasswordGate` in
   * `app/login/page.tsx` now stands between sign-in and the dashboard while this is true, and
   * `PasswordGateScreen` does the same on the handset.
   *
   * The server still REPORTS and never refuses (see the column's comment in schema.prisma), for the
   * reason that route gives: `POST /api/auth/change-password` needs a bearer token, so a 403 at the
   * door would be a demand the account could never satisfy. Read `mustChangePassword` in
   * `lib/signIn.ts` rather than this field directly — an absent field means "a server older than the
   * column", which is neither "must" nor "need not".
   *
   * The admin's "Password link" action on /users remains the OTHER way this is cleared, and it is
   * the one to reach for when somebody cannot sign in at all: /set-password clears the flag as part
   * of the redemption, so the gate is never met by a person who arrived through a link.
   */
  mustChangePassword?: boolean;
  /** ISO-8601. Null alongside a Google account means "never had a password", which is the
   *  distinction this column was added to make; see schema.prisma. */
  passwordSetAt?: string | null;
};

export type FieldProvenanceEntry = { by?: string; byName?: string; at?: string };
export type FieldProvenance = Record<string, FieldProvenanceEntry>;
export type ExtraMetadata = { fieldProvenance?: FieldProvenance } & Record<string, unknown>;

/**
 * `GET /reference/address` — the state list the API validates writes against, served so a form's
 * dropdown and the validator cannot hold different lists (backend/app/services/address.py).
 * `statesAndUnionTerritories` is the flat list a single-group dropdown binds to; the two grouped
 * arrays are the same names split for a form that wants a labelled "Union territories" heading.
 */
export type AddressReference = {
  version: number;
  states: string[];
  unionTerritories: string[];
  statesAndUnionTerritories: string[];
  /**
   * The 795 districts, keyed by the state they belong to — the same shape and the same call as the
   * state list, because a district dropdown that fetches its own options after a state is chosen
   * stalls visibly on a field connection and can briefly disagree with what the server validates
   * against. `byState` covers every name in `statesAndUnionTerritories`, so a chosen state always
   * has options.
   *
   * OPTIONAL IN THE TYPE, not in the contract. The frontend and the API deploy separately, so there
   * is a window in which this build is talking to a backend that predates the district list — and
   * the district dropdown is a REQUIRED field, so the difference between "no options" and a crash
   * is the difference between a form somebody can still submit and a white screen. Marked here so
   * the compiler makes every reader deal with it rather than trusting the version on the other end.
   */
  districts?: {
    source: string;
    sourceUrl: string;
    /** The date the list was compiled, so an export can record which vintage it was coded against. */
    asOf: string;
    listVersion: number;
    count: number;
    byState: Record<string, string[]>;
    normalisation: { trailingWordsStripped: string[]; description: string };
  };
  pincode: { length: number; pattern: string; description: string };
};

export type ArtisanAnswer = {
  responseId: string;
  questionId: string;
  prompt?: string | null;
  sectionCode?: string | null;
  sectionTitle?: string | null;
  sortOrder?: number;
  answerText?: string | null;
  notes?: string | null;
  interviewId: string;
  interviewTitle?: string | null;
  interviewDate?: string | null;
  answeredByName?: string | null;
};

export type ArtisanInterview = {
  interviewId: string;
  title: string;
  notes?: string | null;
  interviewDate?: string | null;
  place?: string | null;
  language?: string | null;
  status?: string | null;
  artisanCount?: number;
  coArtisans?: string[];
  media?: MediaFile[];
};

export type ArtisanQuestionnaire = {
  artisanId: string;
  answered: ArtisanAnswer[];
  total: number;
  // Every interview this artisan belongs to (alone, in a subset, or in a larger set), with recordings.
  interviews?: ArtisanInterview[];
};

/**
 * Two answers to two different questions, in one payload. See `components/forms/LocationFields`.
 *
 * PROVENANCE — `latitude`, `longitude`, `altitude`, `accuracy`, `capturedAt`, `placeName`,
 * `address`. Where the DEVICE was. Filled automatically, never presented as the subject's address.
 *
 * STATED — `state`, `district`, `village`, `pincode`, `subjectLatitude`, `subjectLongitude`. Where
 * the SUBJECT is, said by the researcher. The geocoder may offer these; only a person writes one.
 */
export type LocationPayload = {
  latitude?: number | "";
  longitude?: number | "";
  altitude?: number | "";
  accuracy?: number | "";
  /** ISO 8601. When the device produced the fix above — provenance is not provenance without it. */
  capturedAt?: string;
  address?: string;
  placeName?: string;
  /** Canonical name from `AddressReference`; the API rejects anything off that list. */
  state?: string | null;
  /** Canonical district of `state`, from `AddressReference.districts.byState`. */
  district?: string | null;
  /** The village or hamlet. Free text — no closed list of Indian villages exists. */
  village?: string | null;
  /** Bare 6 digits, no separators — the API normalises "380 001" but stores "380001". */
  pincode?: string | null;
  /** The researcher's optional pin on the SUBJECT'S place. Both or neither; never the device fix. */
  subjectLatitude?: number;
  subjectLongitude?: number;
};

export type Craft = {
  id: string;
  name: string;
  localName?: string | null;
  category?: string | null;
  description?: string | null;
  place?: string | null;
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  // The workshop this craft was documented at. Nullable everywhere: it was added after the join
  // tables, so historical rows carry none. The API hydrates `workshop` alongside the scalar id.
  workshopId?: string | null;
  /** The design & prototype workshop this record is filed under. See `Artisan`. */
  designWorkshopId?: string | null;
  workshop?: Workshop | null;
  extraMetadata?: ExtraMetadata | null;
  createdAt?: string;
};

export type Artisan = {
  id: string;
  name: string;
  localName?: string | null;
  gender?: string | null;
  phone?: string | null;
  email?: string | null;
  place: string;
  address?: string | null;
  notes?: string | null;
  /**
   * The artisan deduplication key (UNIQUE server-side): the same person documented at two workshops
   * resolves to one record through this column. It arrives in TWO shapes under the same name, which
   * is why it is typed as a plain nullable string rather than as 12 digits — the artisan record
   * itself (`GET /artisans/{id}`, i.e. the edit form) returns the FULL number, while the data
   * browser, the .xlsx report and CSV exports return it MASKED ("XXXX XXXX 9012"). Treat any value
   * as regulated personal data: never render it in a list, a card or an export view.
   */
  aadhaarNumber?: string | null;
  /**
   * Date of birth, ISO. The design workshop's participant table shows an AGE, derived from this
   * server-side on every read — an age stored would be wrong within a year with nothing to say so.
   * Both this and `experienceYears` exist because the workshop declares them as fields the
   * reference picker fills in, and until the columns existed importing an artisan left both blank
   * and adding one had nowhere to put them.
   */
  dateOfBirth?: string | null;
  /**
   * The date the artisan began practising the craft, ISO — the feeder `experienceYears` is derived
   * from, and the same argument `dateOfBirth` above makes one answer later: a stated NUMBER of years
   * is right on the day it is typed and silently wrong from then on, while a stated DATE is right
   * every time it is read. Null on every row written before 2026-08-23; the migration that added the
   * column deliberately refuses to guess one from the number.
   */
  craftStartDate?: string | null;
  /**
   * Years practising the craft AS SOMEBODY STATED IT. 0..90, matching the stage registry's own
   * bounds. It is the second of three answers, not the only one: the derived value from
   * `craftStartDate` outranks it wherever a row has one, and the legacy `extraMetadata` spellings
   * sit behind it. See `lib/recordDerivations` for the client's port of that rule.
   */
  experienceYears?: number | null;
  /** Does the artisan hold a PM Vishwakarma Pehchan card? Defaults to true on create. */
  pehchanCardAvailable?: boolean;
  /** Only ever set while `pehchanCardAvailable` is true — the API nulls it whenever the answer is No. */
  pehchanCardNumber?: string | null;
  // Newline-separated, numbered Do's (positive prompt) and Don'ts (negative prompt). Required on new
  // records; existing rows may be null until backfilled.
  dos?: string | null;
  donts?: string | null;
  status: RecordStatus;
  craftId?: string | null;
  craft?: Craft | null;
  workshopId?: string | null;
  /**
   * THE DESIGN & PROTOTYPE WORKSHOP this record is filed under. Added 2026-08-28 with the column.
   *
   * A SECOND LINK BESIDE `workshopId`, NOT A REPLACEMENT. `workshopId` is the ordinary field
   * workshop, gated by `WorkshopAssignment`; this is the 22-stage design and prototype record,
   * gated by `load_workshop_or_404` — creator, admin, or a `DesignWorkshopViewer` grant. Two
   * tables, two access systems; a record may carry either, both or neither.
   * `Artisan.designWorkshopId` in `backend/prisma/schema.prisma` holds the argument.
   *
   * OPTIONAL, AND AN EXPLICIT `null` UNFILES on the way back: the key is in
   * `records.CLEARABLE_KEYS`, so a `null` survives `clean_data` instead of being stripped as an
   * unset optional. Omitting it leaves the stored link alone.
   */
  designWorkshopId?: string | null;
  workshop?: Workshop | null;
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  extraMetadata?: ExtraMetadata | null;
  createdById?: string;
  createdBy?: User;
  createdAt: string;
};

/**
 * The artisan already holding an identity number. Deliberately just enough to recognise a person and
 * navigate to them — the API returns it to a caller who already possesses the number they searched
 * with, and nothing more than name/place/craft/workshop is needed to answer "is this the same man?".
 */
export type ArtisanIdentityMatch = {
  id: string;
  name: string;
  place?: string | null;
  craft?: string | null;
  workshop?: string | null;
};

/**
 * `GET /artisans/lookup/aadhaar` — the artisan form's pre-flight duplicate check. It never 404s:
 * `{found: false}` is the expected, successful answer.
 */
export type AadhaarLookupResult = { found: boolean; artisan?: ArtisanIdentityMatch | null };

/**
 * The HTTP 409 `detail` from POST/PATCH /artisans when an identity number is already on another
 * record. It is an object, not a string, so it must be read off `ApiError.payload` — `ApiError.message`
 * stringifies to "[object Object]" for structured details.
 */
export type ArtisanIdentityConflict = {
  code: "artisan_identity_conflict";
  field: "aadhaarNumber" | "pehchanCardNumber";
  message: string;
  existingArtisan?: ArtisanIdentityMatch;
  maskedValue?: string | null;
};

/**
 * Which KIND of workshop a row records.
 *
 * A Design & Prototype Development Workshop and an ordinary documentation visit are both
 * `Workshop` rows, and nothing distinguished them — so the design-workshop picker had to offer
 * every craft-documentation visit ever recorded. Only a design-prototype workshop carries the
 * sanction, cluster and dates a 22-stage record's cover page is built from.
 *
 * `OTHER` is the default, which is what every row recorded before the column implicitly was.
 */
export type WorkshopType = "DESIGN_PROTOTYPE" | "OTHER";

export const WORKSHOP_TYPE_LABELS: Record<WorkshopType, string> = {
  DESIGN_PROTOTYPE: "Design & Prototype Development Workshop",
  OTHER: "Other workshop"
};

export type Workshop = {
  id: string;
  title: string;
  workshopType?: WorkshopType;
  /**
   * Hydrated by `GET /workshops` — `RELATIONS` in the route includes it. Declared here because the
   * design-workshop picker reads the state and district off it to fill a 22-stage record's cover,
   * and the type not admitting a field the API has always returned is how that read looks wrong.
   */
  location?: LocationPayload | null;
  date: string;
  startDate?: string | null;
  endDate?: string | null;
  place: string;
  description?: string | null;
  notes?: string | null;
  status: RecordStatus;
  artisans?: Array<{ artisan: Artisan }>;
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  extraMetadata?: ExtraMetadata | null;
  createdById?: string;
  createdBy?: User;
  createdAt: string;
};

/**
 * Answer from `GET /workshops/{id}/submission-check` — what submitting a record into one workshop
 * would mean for the current user, asked BEFORE the record is sent.
 *
 * - `canSubmit` false: the workshop has assignments and the user is not one of them, so a create
 *   would be refused with 403. Warn at select time rather than at save time.
 * - `needsAdminApproval` true: the submission is accepted but forced to PENDING, and only an admin
 *   or master admin may approve it. Admins never see this (they are the approval authority), so an
 *   admin submitting late sees `outOfWindow` true with `needsAdminApproval` false.
 */
export type WorkshopSubmissionCheck = {
  workshopId: string | null;
  title?: string | null;
  endDate?: string | null;
  isOver: boolean;
  outOfWindow: boolean;
  needsAdminApproval: boolean;
  assigned: boolean;
  canSubmit: boolean;
};

export type MediaFile = {
  id: string;
  originalFilename: string;
  mediaType: MediaType;
  mimeType: string;
  sizeBytes: number | string;
  objectKey: string;
  url?: string | null;
  caption?: string | null;
  /**
   * `sha256:<hex>` of the stored bytes, written by the upload (`computeChecksum` in lib/media.ts,
   * §2.8 of docs/MEDIA_PIPELINE.md) and returned by the API on every media row.
   *
   * OPTIONAL, AND THE ABSENCE MEANS "UNKNOWN", NEVER "DIFFERENT". It is null for anything that
   * predates the checksum (seeded and legacy rows) and for any file over the 32 MiB hashing ceiling,
   * so duplicate detection may only ever conclude "these are the same" from two checksums that are
   * both present and equal — a comparison that treats null as a distinct value would report every
   * legacy photograph as unique.
   */
  checksum?: string | null;
  linkedRecordType?: string | null;
  linkedRecordId?: string | null;
  /**
   * THE DESIGN & PROTOTYPE WORKSHOP a MISCELLANEOUS upload was filed under, from that form's own
   * dropdown — never derived from the pair above.
   *
   * `linkedRecordType: "designWorkshop"` says which RECORD a file was uploaded against and is
   * written by the upload path for every stage photograph; this says which workshop a person CHOSE
   * to file a loose file under. `records.media_relation_data` on the server carries the argument for
   * why deriving one from the other would break the orphan-recovery machinery. A file may hold one,
   * both or neither.
   */
  designWorkshopId?: string | null;
  /**
   * The client-written Json column beside the file — read for exactly one key today.
   *
   * DECLARED BECAUSE A PROPERTY TYPESCRIPT CANNOT SEE IS A PROPERTY NOTHING READS. It has been on the
   * wire all along (`MediaFile.extraMetadata` in `schema.prisma`; `records._redact_sensitive` scrubs
   * password hashes, identity numbers and media URLs, and nothing else), and the web has only ever
   * WRITTEN it — `ProductForm` and `ToolForm` stamp `{ purpose: MEASUREMENT_GRID_PURPOSE }` on a grid
   * capture. `mediaNoteGrammar.isMeasurementGridRow` is the first reader, and without this line a
   * `MediaFile` would still be assignable to its optional-property row shape while the value read
   * `undefined` for ever: the filter would never fire, and the symptom is a count one too high on
   * exactly the records somebody measured most carefully. Silent, plausible, and printed.
   *
   * `unknown` and not a shape: it is a free Json column several clients write different keys into, and
   * a declared shape here would be a promise this type cannot keep. Narrow it at the reader.
   */
  extraMetadata?: unknown;
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  status: RecordStatus;
  transcriptText?: string | null;
  transcriptSummary?: string | null;
  transcriptStatus?: string | null;
  transcriptError?: string | null;
  /**
   * When a person last replaced this transcript, or null when nothing is on record.
   *
   * NULL IS NOT "NEVER EDITED". The column landed on 2026-08-31 and is null for every row stored
   * before it, so a reader may draw "Edited" from a value and must draw NOTHING from its absence —
   * `POST /media/{id}/transcript` has been able to replace a transcript all along, and printing
   * "these are the machine's words" over one a researcher rewrote is the claim the flag exists to
   * stop being made. The migration carries the full argument.
   */
  transcriptEditedAt?: string | null;
  /** Who made that edit. An audit stamp with no relation behind it — see the schema. */
  transcriptEditedById?: string | null;
  uploadedBy?: User | null;
  createdAt: string;
};

export type ProductDocumentation = {
  id: string;
  craftName: string;
  place: string;
  artisanName: string;
  productName: string;
  localName?: string | null;
  productType: string;
  timeTakenToCompleteProduct?: string | null;
  size?: string | null;
  lengthInches?: string | number | null;
  breadthInches?: string | number | null;
  heightInches?: string | number | null;
  measurementImageId?: string | null;
  measurementAnalysis?: Record<string, unknown> | null;
  measurementAnalysisStatus?: string | null;
  costOfMaking?: string | number | null;
  sellingPrice?: string | number | null;
  marketDemand: string;
  rawMaterialsUsed?: string | null;
  mainToolsUsed?: string | null;
  productFunctionUse?: string | null;
  remarks?: string | null;
  status: RecordStatus;
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  artisanId?: string | null;
  craftId?: string | null;
  workshopId?: string | null;
  /** The design & prototype workshop this record is filed under. See `Artisan`. */
  designWorkshopId?: string | null;
  workshop?: Workshop | null;
  media?: MediaFile[];
  extraMetadata?: ExtraMetadata | null;
  createdById?: string;
  createdBy?: User;
  createdAt: string;
};

export type ToolDocumentation = {
  id: string;
  craftName: string;
  place: string;
  artisanName: string;
  toolkitName: string;
  localName?: string | null;
  englishName?: string | null;
  processUsedIn?: string | null;
  material?: string | null;
  yearsInUse?: number | null;
  /**
   * TWO HEIGHTS, AND THE PAIR IS DELIBERATE — read both before writing either.
   *
   * `height` is the original column and it declares no unit: not in its name, not in
   * `schema.prisma`, not on the label a designer reads. It holds every number already typed into it,
   * in whatever unit that person had in mind, and it is NOT being migrated — the tool form still
   * draws its box and still saves it.
   *
   * `heightInches` was added on 2026-08-27 to end the defect that absence caused, and the schema's
   * own comment states it: an accepted machine reading of a tool's height "landed in the plain
   * `height` column above, which declares no unit — losing the one fact the column name is there to
   * carry". It is also the only one of the two a method marker can name, because
   * `services/measurement_provenance.DIMENSION_FIELDS` is exactly `lengthInches` / `breadthInches` /
   * `heightInches`. Both measurement routes on `ToolForm` propose into it.
   *
   * `string | number | null` like every dimension here, and not `number`: these are Prisma
   * `Decimal(10, 2)` columns and a `Decimal` arrives over the wire as a JSON STRING. Read one behind
   * `Number.isFinite(Number(v))`; seed an input with `String(v)`.
   *
   * Verified 2026-08-27 against `grep -n heightInches backend/prisma/schema.prisma
   * backend/app/schemas/records.py backend/app/api/routes/tools.py` — the column, both request
   * schemas, and `_CLEARABLE_COLUMNS` (which is what lets emptying the box empty the column).
   */
  height?: string | number | null;
  width?: string | number | null;
  lengthInches?: string | number | null;
  breadthInches?: string | number | null;
  heightInches?: string | number | null;
  measurementImageId?: string | null;
  measurementAnalysis?: Record<string, unknown> | null;
  measurementAnalysisStatus?: string | null;
  thickness?: string | number | null;
  weight?: string | number | null;
  radius?: string | number | null;
  maker: string;
  traditionType: string;
  replacementCost?: string | number | null;
  suggestionsForToolImprovement?: string | null;
  remarks?: string | null;
  status: RecordStatus;
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  artisanId?: string | null;
  craftId?: string | null;
  workshopId?: string | null;
  /** The design & prototype workshop this record is filed under. See `Artisan`. */
  designWorkshopId?: string | null;
  workshop?: Workshop | null;
  media?: MediaFile[];
  extraMetadata?: ExtraMetadata | null;
  createdById?: string;
  createdBy?: User;
  createdAt: string;
};

export type QuestionnaireQuestion = {
  id: string;
  sectionId?: string | null;
  sectionCode: string;
  sectionTitle: string;
  prompt: string;
  sortOrder: number;
  isActive: boolean;
};

export type QuestionnaireSection = {
  id: string;
  code: string;
  title: string;
  sortOrder: number;
  isActive: boolean;
  questions: QuestionnaireQuestion[];
};

export type QuestionnaireResponse = {
  id: string;
  questionId: string;
  answerText?: string | null;
  notes?: string | null;
  question?: QuestionnaireQuestion;
  answeredBy?: User;
};

export type QuestionnaireInterview = {
  id: string;
  title: string;
  interviewDate?: string | null;
  place?: string | null;
  language?: string | null;
  notes?: string | null;
  status: RecordStatus;
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  workshopId?: string | null;
  /** The design & prototype workshop this record is filed under. See `Artisan`. */
  designWorkshopId?: string | null;
  workshop?: Workshop | null;
  artisans?: Array<{ artisan: Artisan }>;
  responses?: QuestionnaireResponse[];
  media?: MediaFile[];
  createdBy?: User;
  createdById: string;
  createdAt: string;
};

export type DataAccessTier = "DOWNLOAD" | "COMMENT" | "EDIT";
export type DataAccessStatus = "PENDING" | "GRANTED" | "DENIED" | "REVOKED";

export type DataAccessScopeItem = { id?: string; recordType: string; recordId: string };

export type DataAccessGrant = {
  id: string;
  ownerId: string;
  granteeId: string;
  tier: DataAccessTier;
  status: DataAccessStatus;
  allData: boolean;
  requestNote?: string | null;
  decisionNote?: string | null;
  owner?: User;
  grantee?: User;
  requestedBy?: User | null;
  decidedBy?: User | null;
  scopeItems?: DataAccessScopeItem[];
  decidedAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
};

export type MyGrants = { incoming: DataAccessGrant[]; outgoing: DataAccessGrant[] };

export type TierInfo = { tier: DataAccessTier; description: string };

export type EntryComment = {
  id: string;
  recordType: string;
  recordId: string;
  authorId: string;
  body: string;
  author?: User;
  createdAt: string;
  updatedAt?: string;
};

export type RecordRevision = {
  id: string;
  recordType: string;
  recordId: string;
  editedById?: string | null;
  editedBy?: User | null;
  changes: Record<string, { old: unknown; new: unknown }>;
  createdAt: string;
};

export type TaskStatus = "OPEN" | "IN_PROGRESS" | "DONE" | "CANCELLED";

export type AssignedTask = {
  id: string;
  title: string;
  description?: string | null;
  status: TaskStatus;
  dueAt?: string | null;
  completedAt?: string | null;
  recordType?: string | null;
  recordId?: string | null;
  assigneeId: string;
  createdById: string;
  assignee?: User;
  createdBy?: User;
  createdAt: string;
  updatedAt: string;
};

export type WorkshopAssignment = {
  id: string;
  workshopId: string;
  userId: string;
  user?: User;
  assignedBy?: User | null;
  createdAt?: string;
  /**
   * The row's state. `GET /workshops/{id}/assignments` returns EVERY row on the workshop — pending
   * requests, denials and revocations included — and only GRANTED confers access. A caller building
   * "who is assigned" must filter on this; taking every `userId` treats a refused request as a member.
   */
  status?: "PENDING" | "GRANTED" | "DENIED" | "REVOKED";
  accessLevel?: string;
};

export const productTypes = ["FINISHED_GOOD", "SAMPLE", "RAW_MATERIAL", "COMPONENT", "PACKAGING", "OTHER"];
export const marketDemandOptions = ["LOW", "MEDIUM", "HIGH", "SEASONAL", "UNKNOWN"];
export const makerOptions = ["ARTISAN", "LOCAL_BLACKSMITH", "CARPENTER", "WORKSHOP", "FACTORY", "UNKNOWN", "OTHER"];
export const traditionOptions = ["TRADITIONAL", "MODERN", "HYBRID", "UNKNOWN"];
export const mediaTypes: MediaType[] = ["IMAGE", "VIDEO", "AUDIO", "PDF", "DOCUMENT", "OTHER"];
