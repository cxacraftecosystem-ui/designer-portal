export type UserRole =
  | "MASTER_ADMIN"
  | "ADMIN"
  | "PROFESSOR"
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
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  status: RecordStatus;
  transcriptText?: string | null;
  transcriptSummary?: string | null;
  transcriptStatus?: string | null;
  transcriptError?: string | null;
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
  height?: string | number | null;
  width?: string | number | null;
  lengthInches?: string | number | null;
  breadthInches?: string | number | null;
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
