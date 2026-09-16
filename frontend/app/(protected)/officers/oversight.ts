/**
 * THE SIXTH SCOPE, CLIENT-SIDE: who supervises a design & prototype workshop, and the artisan list.
 *
 * `backend/app/services/design_workshop_oversight.py` is the argument in full and this file is the
 * wire. What follows is the part a caller on this side has to hold in their head, because each of
 * these is a place a reasonable instinct gives the wrong answer.
 *
 * ── 1. TWO DOORS, DISJOINT AUDIENCES, AND NEITHER IS THE OTHER'S SUPERSET ─────────────────────
 *
 * `canAssignWorkshopOversight` is `{MINISTRY_ADMIN, ADMIN, MASTER_ADMIN}` and
 * `canReadWorkshopOversight` is `{ASSISTANT_DIRECTOR, REGIONAL_DIRECTOR, MINISTRY_ADMIN}`. An ADMIN
 * may assign and may not be assigned; an ASSISTANT DIRECTOR the reverse; a MINISTRY_ADMIN is in
 * both. **A REGIONAL DIRECTOR IS REFUSED THE ASSIGNMENT SCREEN EVEN THOUGH THEY OUTRANK AN
 * ASSISTANT DIRECTOR** — the supervised do not choose the supervisor.
 *
 * ── 2. WHY THESE PAGES EXIST AT ALL — AND THE REASON CHANGED ON 2026-09-14 ────────────────
 *
 * THE ORIGINAL REASON, KEPT BECAUSE IT EXPLAINS THE SHAPE OF THIS MODULE: an officer was outside
 * `DESIGN_WORKSHOP_ROLES`, `load_workshop_or_404` refused anybody outside that frozenset before it
 * looked at anything, and so `/design-workshops/{id}` and every page beneath it answered a **404**
 * to an Assistant Director or a Regional Director. These pages are the surface that was built
 * instead, and that is why they render the inspection surface's components rather than the
 * workshop's own.
 *
 * **THAT IS NO LONGER THE WHOLE REASON.** The three directorate tiers joined
 * `DESIGN_WORKSHOP_ROLES` on the owner's ruling, so the blanket role refusal at the top of
 * `load_workshop_or_404` is gone. **THAT IS NOT THE SAME AS "THE WORKSHOP TREE IS NOW REACHABLE",
 * and reading it that way has already shipped as a 404 on /officers.** Role gates the GRANT arm and
 * only the grant arm: the loader admits on `record.createdById === user.id`, OR `is_admin(user)`,
 * OR (`can_run_design_workshops(user)` AND a `DesignWorkshopViewer` row) — an `and`, so a directorate
 * tier holding no grant is still turned away. Reaching a workshop needs the creator arm, an admin,
 * or a viewer grant, and an oversight assignment is none of the three.
 *
 * What keeps these pages worth having is therefore the second half, which did not change at all: a
 * monitored workshop is one an officer was ASSIGNED, the assignment is what `readOnly: true` below
 * is about, and an officer holding an oversight assignment is not thereby a designer on that
 * workshop.
 *
 * AND ONE THING THAT DOES STILL REFUSE THEM, WHICH IS WORTH KNOWING HERE: an officer may not author
 * the workshop their OWN sanction order opened —
 * `services/design_workshops._refuse_if_the_officer_is_authoring_what_they_sanctioned` answers 403
 * on the write and leaves the read alone, so a monitored workshop can still be read end to end.
 *
 * ── 3. `readOnly: true` IS ON THE WIRE ON PURPOSE ─────────────────────────────────────────────
 *
 * Both clients will eventually render the officer's payload through the same screen as the
 * designer's read, and a screen that cannot tell the two apart offers a Save button the API answers
 * 404 to. An ABSENT flag is treated as read-only here, which is the opposite of how `truncated` is
 * treated — see {@link oversightIsReadOnly}.
 *
 * ── 4. WHAT THE OFFICER'S READ DELIBERATELY DOES NOT CARRY ────────────────────────────────────
 *
 * `transcripts` is absent, and it is an absence with a decision behind it: an officer holds no
 * `DataAccessGrant`, no upload of their own and no viewer row, so the media predicate would cost a
 * query to produce an empty list — and would put that route on the media path at all, where the
 * next person widening that predicate would widen this surface without noticing. **Whether an
 * officer should see the photographs and recordings is an owner's decision that has not been
 * made**, so today the answer is no, stated once. A screen over this payload must therefore SAY
 * that media are not part of an oversight read rather than draw an empty gallery — the two look
 * identical and only one of them is true.
 *
 * ── 5. THE PUT IS PER CAPACITY, AND AN OMITTED KEY IS NOT A NULL ──────────────────────────────
 *
 * Omitting a key leaves that capacity exactly as it stands; sending `null` deletes the row. Three
 * requests, three outcomes. A screen that always sends both fields can never mean "leave the other
 * one alone", which is why {@link putWorkshopOversight} takes a partial and why callers must build
 * it from what the reader actually changed.
 *
 * ── 6. WHY THIS FILE IS NOT IN `lib/` ─────────────────────────────────────────────────────────
 *
 * Because every other client module for a design-workshop scope is, and this one should be too:
 * `lib/designWorkshopInspections.ts` is its exact shape one scope over. It is colocated under the
 * route it serves because `frontend/lib/` was owned by another workstream in the wave that wrote
 * it, and a cross-workstream edit there silently destroys the other change. **Moving it to
 * `lib/designWorkshopOversight.ts` is a rename and nothing else** — nothing here imports from the
 * route tree.
 */

import { ApiError, API_BASE, apiFetch, assertApiConfigured, buildQuery, getToken } from "@/lib/api";
import { designerCreateFields } from "@/lib/designWorkshops";
import type { DwStageCompleteness, DwStageData, DwSummary } from "@/lib/designWorkshops";
import type { PageResult } from "@/lib/types";

/** How long a search box on either picker may get. Mirrors the routes' `Query(max_length=120)`. */
export const OVERSIGHT_SEARCH_MAX = 120;

/** The two capacities, in the order a report prints them. Mirrors `CAPACITIES` on the server. */
export const CAPACITIES = ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"] as const;
export type DwOversightCapacity = (typeof CAPACITIES)[number];

export const CAPACITY_LABELS: Record<DwOversightCapacity, string> = {
  ASSISTANT_DIRECTOR: "Assistant Director",
  REGIONAL_DIRECTOR: "Regional Director"
};

/** One oversight row as the assignment screen reads it. */
export type DwOversightAssignment = {
  capacity: DwOversightCapacity;
  userId: string;
  /**
   * `name`/`email`/`role` travel WITH the row rather than being joined against the directory this
   * screen also holds: an officer whose account has since been suspended is precisely the row an
   * administrator most needs to see and act on, and a join against the eligible list would render
   * it as a bare cuid.
   */
  name: string;
  email: string;
  role: string;
  /** `assignedAt`, never `createdAt` — "since when has THIS PERSON been its AD". */
  assignedAt: string | null;
  assignedById: string | null;
};

/**
 * One designer on a workshop, as the assignment screen reads them.
 *
 * `isLead` IS THE SERVER'S ANSWER AND IS NEVER RE-DERIVED HERE. There is no lead column on
 * `DesignWorkshopViewer` — the lead is the viewer whose own profile would write the promoted
 * `designerName` the workshop carries — so the rule lives in one place server-side
 * (`_the_lead_among`) and both the display and the removal read the same answer. A client that
 * guessed would mark one person on screen and the write would move another.
 *
 * **EVERY ROW CARRIES THE KEY, INCLUDING `false`.** An absent `isLead` would be indistinguishable
 * from "this server does not answer the question", which is a different fact from "not the lead".
 */
export type DwNamedDesigner = {
  userId: string;
  name: string;
  email: string;
  role: string;
  isLead: boolean;
};

export type DwWorkshopOversight = {
  workshopId: string;
  title: string | null;
  status: string;
  designerName: string | null;
  /**
   * Who may open this workshop. **NEW IN 0.0.12, AND ITS ABSENCE WAS THE WHOLE DEFECT** — this read
   * answered `designerName` as a bare string, so the one screen that decides who a workshop is for
   * could not see who currently held it, could not pre-tick a picker and had nothing to compare a
   * change against.
   *
   * OPTIONAL ON THE TYPE, because the browser bundle and the API ship separately: an API that
   * predates the key answers without it, and a screen that read `detail.designers.length` would
   * throw rather than say "this server cannot tell me yet". Treat `undefined` as unknown and `[]` as
   * "nobody but whoever opened it" — **the creator is not in this list**, they hold the workshop
   * through `createdById` and have no viewer row.
   */
  designers?: DwNamedDesigner[];
  oversight: DwOversightAssignment[];
};

export type DwOfficer = {
  id: string;
  name: string;
  email: string;
  role: string;
  /**
   * Which slots this account may be filed in — **empty for a MINISTRY_ADMIN**, which is the point.
   * The picker greys such a row out with the reason rather than letting the PUT 422 it: a directory
   * that offers a choice the write refuses teaches people to distrust it.
   */
  capacities: DwOversightCapacity[];
};

export type DwOfficerDirectory = { users: DwOfficer[]; truncated: boolean };

/**
 * The officer's designer picker. **FOUR KEYS AND NO ROSTER COLUMNS**, and the absence is the point:
 * `rosterId`, `rosterActive`, `canSignIn`, `firstSeenAt`, `hasProfile` and `institution` are facts
 * about the EMPANELMENT roster, which is the admin's table — and whether a designer has a
 * suspension on file is not an officer's business. The suspended are already absent by the time
 * this renders, because the roster fold is inside the server query's WHERE.
 */
export type DwAssignableDesigner = { id: string; name: string; email: string; role: string };

export type DwAssignableDesignerList = { users: DwAssignableDesigner[]; truncated: boolean };

/** One thing the importer could not do cleanly, in terms an officer can act on. */
export type ImportProblem = {
  sheet: string;
  /**
   * The 1-based worksheet row exactly as Excel's row gutter shows it, so "row 34" means press
   * Ctrl+G and type 34. **`null` means the problem is about the SHEET rather than about a row** —
   * an ignored column, a second sheet that was not used.
   */
  row: number | null;
  severity: "error" | "warning";
  reason: string;
  /**
   * The offending cell, **already masked by the server** for any identity number. Nothing on this
   * side re-masks it: a mask applied in two places is a mask that can be forgotten in one.
   */
  value?: string | null;
};

export type ArtisanImportReport = {
  importId: string;
  sheet: string | null;
  rowsRead: number;
  artisansCreated: number;
  artisansLinked: number;
  rowsRefused: number;
  participantsCreated: number;
  problems: ImportProblem[];
};

export type DwArtisanImport = {
  id: string;
  sourceFilename: string | null;
  sheetName: string | null;
  rowsRead: number;
  artisansCreated: number;
  artisansLinked: number;
  rowsRefused: number;
  participantsCreated: number;
  problems: ImportProblem[];
  uploadedBy: string | null;
  createdAt: string | null;
};

export type DwArtisanImportList = { imports: DwArtisanImport[] };

/** The officer's read of one workshop — the designer's payload minus everything that writes. */
export type DwOversightDetail = DwSummary & {
  /** Keyed by stage key, exactly as the designer's read groups them — same serialiser, not a copy. */
  stages: Record<string, DwStageData>;
  completeness: Record<string, DwStageCompleteness>;
  schemaVersion: string;
  /** A second string, never folded into the one above: the registry's version and the workshop's
   *  own custom-section version move independently. */
  customSchemaVersion?: string;
  /** Who supervises this workshop, so the officer reading it can see the other capacity. */
  oversight?: DwOversightAssignment[];
  /**
   * The server's own word for "this is a read". See {@link oversightIsReadOnly}, which is the only
   * thing that should ever read this key directly.
   */
  readOnly?: boolean;
};

// --- The assignment screen ------------------------------------------------------------------

export function listOfficers(search?: string) {
  return apiFetch<DwOfficerDirectory>(
    `/design-workshop-oversight/officers${buildQuery({ search: search || undefined })}`
  );
}

export function listAssignableDesigners(search?: string) {
  return apiFetch<DwAssignableDesignerList>(
    `/design-workshop-oversight/designers${buildQuery({ search: search || undefined })}`
  );
}

/**
 * The workshops an assigner may name people on — **every one of them**.
 *
 * ⚠ **`GET /design-workshops` IS THE WRONG LIST HERE AND IT FAILS SILENTLY.** That route does not
 * refuse a Ministry Admin; it scopes anybody who is not an admin to the viewer relation, which a
 * Ministry Admin holds on no workshop — so it answers 200 with an EMPTY page, on the first control
 * of the assignment screen, in a repository full of workshops. `DesignWorkshopSelect` is built over
 * that route, which is why this screen does not use it.
 */
/**
 * Whether a workshop has a designer named on it. **A WORD, NEVER A BOOLEAN**, and the reason is
 * `buildQuery`: it drops `""` exactly as it drops null/undefined, so a `false` that has to survive
 * the query builder cannot be a boolean. It is the same shape, and the same reason, as the workshop
 * scope's reserved word `"none"`.
 *
 * `undefined` means BOTH — by absence, the way every filter in this app says "everything" — and
 * never "false".
 */
export type DwStaffingFilter = "staffed" | "unstaffed";

export function listAssignableWorkshops(params: {
  page?: number;
  pageSize?: number;
  search?: string;
  staffed?: DwStaffingFilter;
}) {
  return apiFetch<PageResult<DwSummary>>(
    `/design-workshop-oversight/workshops${buildQuery({
      page: params.page,
      pageSize: params.pageSize,
      search: params.search || undefined,
      staffed: params.staffed
    })}`
  );
}

/**
 * What the create form on `/officers` sends. **THE THIRD CREATION DOOR.**
 *
 * `POST /design-workshops` is `require_admin` and a MINISTRY_ADMIN is not an admin, so the account
 * that decides who every workshop is for could not open one. `POST /design-workshop-oversight/
 * workshops` is gated on the same predicate as this whole page and goes through the SAME
 * `open_design_workshop` the other two doors use — the shared opener whose four steps (eligibility,
 * create, viewer rows, prefill seed) `tests/test_design_workshop_creation_path.py` exists to keep
 * from being copied a fourth time.
 *
 * WARNING: **THE TWO DESIGNER FIELDS KEEP THEIR CREATE-DOOR MEANINGS EXACTLY**, and this module does
 * not decide them: `designerCreateFields` in `lib/designWorkshops` is the ONE place in the web
 * client that reads a ticked set and a chosen lead into `designerUserId` / `designerUserIds`, and
 * {@link createOversightWorkshop} calls it rather than building the pair here. A second reading
 * would be a second answer to "whose profile is copied into stage 1 and whose name reaches the
 * report".
 *
 * `templateId`, `notes` and `workshopId` are absent from the body BY DESIGN — see the server's
 * `DesignWorkshopOversightCreateIn`. They are the report format, the designer's scratch column and
 * a designer's cross-reference to a `Workshop` row; none of the three is an officer's answer.
 */
export type DwOversightCreateBody = {
  title: string;
  workshopKind?: string;
  /** The ticked designers, in tick order. The lead is resolved from `lead` by the shared rule. */
  designerUserIds?: readonly string[];
  lead?: string;
  craftName?: string;
  clusterName?: string;
  state?: string;
  district?: string;
  /** `yyyy-mm-dd`, exactly what the create form's hidden inputs submit. */
  startDate?: string;
  endDate?: string;
};

/**
 * Open a design & prototype workshop from the oversight screen.
 *
 * **NO OFFLINE ARM, DELIBERATELY.** `createWorkshopOrKeepItHere` exists because a designer opens a
 * workshop in a courtyard with no signal, and its local-draft machinery — `DwDraft.createSentAt`,
 * `resolveInterruptedCreate`, the adopt-or-ask pass — is the compensation for a POST whose answer
 * was lost. `/officers` is an office desktop under the standing web-and-backend-only decision for
 * ministry surfaces, and a local draft created by an account that cannot sync one is a trap rather
 * than a safety net: `createLocalDraft` gates on `canRunDesignWorkshops`, and the draft would then
 * have to be adopted into a workshop this account cannot create through the ordinary door either.
 * A failure here is therefore reported and nothing is kept — which is the honest answer on a desk.
 *
 * Answers the created workshop's summary, the same shape {@link listAssignableWorkshops} lists, so
 * the page can make it the chosen workshop with no second read.
 */
export function createOversightWorkshop(body: DwOversightCreateBody) {
  const { designerUserIds, lead, ...rest } = body;
  return apiFetch<DwSummary>("/design-workshop-oversight/workshops", {
    method: "POST",
    body: JSON.stringify({
      ...rest,
      // `JSON.stringify` drops an `undefined` value, so a key the shared resolver omits never
      // reaches the wire at all — which is what keeps this body answerable by an API that predates
      // either designer field, since every request model in this app is `extra="forbid"`.
      ...designerCreateFields({ chosen: designerUserIds ?? [], lead: lead ?? "" })
    })
  });
}

export function getWorkshopOversight(workshopId: string) {
  return apiFetch<DwWorkshopOversight>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}`
  );
}

/**
 * Set the two capacities. **A key that is absent from `body` is left exactly as it stands; an
 * explicit `null` unassigns.** Build the object from what the reader changed, never from the whole
 * form — a screen that always sends both can never mean "leave the other one alone".
 */
export function putWorkshopOversight(
  workshopId: string,
  body: { assistantDirectorId?: string | null; regionalDirectorId?: string | null }
) {
  return apiFetch<{ oversight: DwOversightAssignment[] }>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}`,
    { method: "PUT", body: JSON.stringify(body) }
  );
}

/**
 * One viewer row named in an answer — who lost access, or who still has it.
 *
 * Structurally {@link DwNamedDesigner} minus `isLead`, and named separately because that is the
 * honest shape: the server answers these lists from `viewer_rows`, which carries no lead flag, and a
 * type that pretended otherwise would invite a screen to draw one.
 */
export type DwViewerRow = { userId: string; name: string; email: string; role: string };

/**
 * The answer to naming a different designer. **TWO KEYS HERE WERE ON THE WIRE AND NOT ON THIS TYPE
 * UNTIL 0.0.12, AND THE OMISSION WAS THE WHOLE "ADD-ONLY" COMPLAINT.**
 *
 * `reassign_designer` has always returned `removedDesigner` and `stillHaveAccess`; this file's
 * return type dropped both, so the only screen an assigner can open never showed that a replacement
 * had taken anybody's access away — or that a co-designer, a redeemed join card or a granted access
 * request still held write access to the workshop. The server was telling the truth to nobody.
 *
 * `removedDesigner: null` means "nobody was IDENTIFIABLE as the outgoing lead", which is a different
 * fact from "nobody had access" — `stillHaveAccess` is what says which. The server resolves the
 * outgoing lead by matching the workshop's promoted `designerName` against what each viewer's own
 * profile would write, and an ambiguous answer removes NOBODY and reports instead of guessing.
 */
export type DwDesignerReassignment = {
  designerId: string;
  designerName: string | null;
  stagesWritten: string[];
  removedDesigner: DwViewerRow | null;
  stillHaveAccess: DwViewerRow[];
};

/**
 * Name ONE designer, replacing whoever the report currently names.
 *
 * ⚠ **NO SCREEN CALLS THIS SINCE 0.0.12, AND IT IS KEPT RATHER THAN DELETED.** `DesignerPanel`
 * moved to {@link putWorkshopDesigners}, because a control that can only replace cannot express
 * "also give the second designer access" or "take the co-designer off" — the two acts the officers
 * page existed to perform and could not. The route behind this is unchanged, still live, still the
 * singular door, and still the one `tests/test_workshop_oversight_reassignment.py` pins the three
 * half-happening defects against; a wire this client cannot speak is a wire that quietly rots, and
 * the plural door's own answer is checked against this one's shape on the server.
 *
 * **Prefer {@link putWorkshopDesigners} for anything new.** It reaches the same replacement — send
 * the set without the outgoing designer and with `leadUserId` naming the incoming one — and it is
 * the only one of the two that can also add or remove somebody without moving the report's author.
 */
export function putWorkshopDesigner(workshopId: string, designerId: string) {
  return apiFetch<DwDesignerReassignment>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}/designer`,
    { method: "PUT", body: JSON.stringify({ designerId }) }
  );
}

/** The answer to a whole-team save: the set as the SERVER now holds it, and who lost access. */
export type DwDesignerTeamSaved = {
  designers: DwNamedDesigner[];
  designerName: string | null;
  stagesWritten: string[];
  removedDesigners: DwViewerRow[];
};

/**
 * Set the whole team this workshop is FOR, and say which of them leads it.
 *
 * WARNING: **A WHOLE-SET BODY IS NOT A WHOLE-SET WRITE.** The server diffs this list against the
 * rows that exist and adds and removes one at a time; it never calls `replace_viewers`, because a
 * whole-set replace destroys a viewer row a concurrent join-card redemption created in the same
 * second. The caller's obligation is the other half of that: **send the WHOLE set, built from
 * eligible union currently-assigned union ticked-this-sitting**, because an id you did not render
 * is an id you are asking the server to remove.
 *
 * `leadUserId` OMITTED MEANS "LEAVE THE LEAD ALONE", which is the ordinary save — adding a
 * co-designer must not move whose profile is copied into stage 1 or whose name the report carries.
 * Sent, it must be one of `userIds`; the server 422s a lead who is not on the workshop rather than
 * silently promoting somebody else.
 *
 * **"NOBODY IS THE DESIGNER" IS NOT EXPRESSIBLE AND THE SERVER SAYS SO**, in two 422s that name the
 * remedy: an empty set on a workshop that names a designer, and a set that drops the lead with no
 * `leadUserId` naming their replacement. Render those verbatim — they are the only sentences that
 * tell an officer what to do next.
 */
export function putWorkshopDesigners(
  workshopId: string,
  body: { userIds: string[]; leadUserId?: string }
) {
  return apiFetch<DwDesignerTeamSaved>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}/designers`,
    { method: "PUT", body: JSON.stringify(body) }
  );
}

/**
 * One artisan on a workshop's roster, as the oversight screen reads them.
 *
 * **SIX KEYS, AND THE ABSENCES ARE THE POINT.** No `aadhaarNumber`, no `pehchanCardNumber`, no
 * `phone`, no `address`, no `dateOfBirth`. An officer may read an unmasked Aadhaar through the
 * artisan record itself — `_may_read_full_aadhaar` is a rank floor at PROFESSOR and all three
 * ministry posts clear it — so nothing is being withheld from them; it is the frontend contract's
 * own rule that a regulated identity number is never rendered in a LIST, a card or an export view.
 *
 * `status` is carried because an imported artisan is created PENDING and enters the review queue,
 * so a roster of fifteen PENDING rows is the normal state of a fresh import and must not read as a
 * fault.
 */
export type DwRosterArtisan = {
  id: string;
  name: string;
  place: string;
  craftName: string | null;
  status: string;
  createdAt: string | null;
};

export type DwRosterList = { artisans: DwRosterArtisan[]; truncated: boolean };

/**
 * Who is on this workshop's artisan roster.
 *
 * **NOTHING IN THIS PRODUCT READ THIS LIST BEFORE 0.0.12, ON EITHER CLIENT.**
 * `GET /artisans?designWorkshopId=...` has existed for as long as the column has and is called by
 * nobody; `ArtisanListPanel` offered a pro-forma, an upload and an import history and never once
 * said who was actually on the list. That absence is why the roster's missing REMOVAL went
 * unnoticed for so long — there was no list to remove anybody from.
 */
export function listWorkshopArtisans(workshopId: string) {
  return apiFetch<DwRosterList>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}/artisans`
  );
}

/**
 * Take one artisan off this workshop's roster. **UNFILES; NEVER DELETES.**
 *
 * `Artisan.designWorkshopId` is a nullable link, so this clears it and leaves the artisan's record,
 * photographs, products, tools and interviews exactly as they are. `Artisan.createdBy` is
 * `Restrict` and is never touched: an officer did not author these records.
 *
 * WARNING: **THE STAGE-3 PARTICIPANT ROW IS LEFT STANDING AND THE SCREEN MUST SAY SO.** The importer
 * writes a second thing per artisan — a `DwStageEntry` under the stage-3 participants collection —
 * and removing it from here would be a STAGE WRITE performed by an officer on a report that may be
 * under inspection. These are two deletions and nothing links them; the participant row is removed
 * by a designer in stage 3.
 *
 * `unlinked: false` is NOT an error. Two officers working one list is the ordinary case, and "that
 * artisan is already off this roster" is a state the second of them should be told about rather
 * than shown a failure over.
 */
export function unlinkWorkshopArtisan(workshopId: string, artisanId: string) {
  return apiFetch<{ unlinked: boolean }>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}/artisans/${encodeURIComponent(
      artisanId
    )}`,
    { method: "DELETE" }
  );
}

export function listArtisanImports(workshopId: string) {
  return apiFetch<DwArtisanImportList>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}/artisan-imports`
  );
}

// --- The officer's own surface --------------------------------------------------------------

export function listOverseenWorkshops(params: {
  page?: number;
  pageSize?: number;
  search?: string;
}) {
  return apiFetch<PageResult<DwSummary>>(
    `/design-workshop-oversight/assigned${buildQuery({
      page: params.page,
      pageSize: params.pageSize,
      search: params.search || undefined
    })}`
  );
}

export function getOverseenWorkshop(workshopId: string) {
  return apiFetch<DwOversightDetail>(
    `/design-workshop-oversight/assigned/${encodeURIComponent(workshopId)}`
  );
}

/**
 * Is this payload read-only? **An ABSENT flag answers `true`.**
 *
 * That is the opposite of how `truncated` is treated two files over, and deliberately: the two
 * absences mean opposite things. A missing `truncated` is a server that did not cut the list, and
 * reading it as "cut" would print a warning about nothing. A missing `readOnly` is a server that
 * did not say — and the only safe reading of silence on a surface whose entire premise is that
 * nothing here writes is that nothing here writes. Defaulting the other way offers a Save button
 * the API answers 404 to.
 */
export function oversightIsReadOnly(
  detail: { readOnly?: boolean } | null | undefined
): boolean {
  return detail?.readOnly !== false;
}

// --- The artisan pro-forma ------------------------------------------------------------------

export type OversightFile = { blob: Blob; fileName: string };

/**
 * Fetch the .xlsx pro-forma by hand, because `apiFetch` cannot.
 *
 * That helper reads every response as JSON or TEXT, and reading a workbook as text hands back a
 * mangled string cast to the caller's type — a download that "succeeds" and produces a file Excel
 * refuses to open.
 *
 * ⚠ **THIS IS A SECOND COPY OF `lib/questionnaireForms.fetchWorkbook`, AND IT SHOULD NOT BE.** That
 * function is private to its module and this one is character-for-character its three obligations:
 * refuse the request when this build has no usable API address, attach the bearer token, and turn a
 * failure body into the sentence the server actually sent rather than "[object Object]". The right
 * shape is one exported helper both doors call — `lib/questionnaireForms.ts` was owned by another
 * workstream in the wave that wrote this, so the copy is deliberate and temporary rather than an
 * oversight. **If you are the person consolidating them, export `fetchWorkbook` and delete this.**
 */
async function fetchWorkbook(path: string, fallbackName: string): Promise<OversightFile> {
  assertApiConfigured();

  const headers = new Headers();
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const response = await fetch(`${API_BASE}/api${path}`, { headers, cache: "no-store" });
  if (!response.ok) {
    const contentType = response.headers.get("content-type") ?? "";
    const payload = contentType.includes("application/json")
      ? await response.json()
      : await response.text();
    const detail =
      typeof payload === "object" && payload && "detail" in payload
        ? (payload as { detail: unknown }).detail
        : undefined;
    // `statusText` is empty over HTTP/2 — which every deployed request is — so it can never be the
    // last resort on its own, or a body-less failure reaches the screen as a blank error box.
    throw new ApiError(
      response.status,
      typeof detail === "string" && detail
        ? detail
        : response.statusText || `The server refused the request (HTTP ${response.status}).`,
      payload
    );
  }
  return {
    blob: await response.blob(),
    fileName: fileNameFromDisposition(response.headers.get("content-disposition")) ?? fallbackName
  };
}

function fileNameFromDisposition(header: string | null): string | null {
  if (!header) return null;
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header);
  return match ? decodeURIComponent(match[1]) : null;
}

/**
 * The blank workbook an officer types a workshop's artisan list into.
 *
 * **PASS THE WORKSHOP ID WHENEVER THERE IS ONE.** With it the Details sheet names the workshop, and
 * the State, District and Venue on that sheet are what blank cells fall back to — so the defaults
 * are VISIBLE in the file rather than applied invisibly at upload time. It is also what the upload
 * checks against the URL, which is what stops fifteen people's regulated records being filed under a
 * stranger's project.
 */
export function downloadArtisanProForma(workshopId?: string | null) {
  return fetchWorkbook(
    `/design-workshop-oversight/artisan-pro-forma${buildQuery({ workshopId: workshopId || undefined })}`,
    "artisan-list-pro-forma.xlsx"
  );
}

/**
 * Upload a filled-in artisan list.
 *
 * MULTIPART WITH ONE PART AND NO SCALARS: the workshop is in the path and everything else is in the
 * workbook. Do not set `Content-Type` — the browser has to write the boundary.
 */
export async function uploadArtisanList(workshopId: string, file: File) {
  const body = new FormData();
  body.append("file", file);
  return apiFetch<ArtisanImportReport>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}/artisans/upload`,
    { method: "POST", body }
  );
}

/** What a .xlsx door accepts, as an `accept` attribute. A FILTER, never the rule. */
export const ARTISAN_ACCEPT =
  ".xlsx,.xlsm,.xltx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

/** 4 MiB — half the questionnaire's, mirroring `MAX_ARTISAN_UPLOAD_BYTES`. */
export const MAX_ARTISAN_UPLOAD_BYTES = 4 * 1024 * 1024;

/**
 * The refusal for a file this door will not take, or `null`.
 *
 * **BOTH CHECKS ARE THE SERVER'S TOO, AND THAT IS THE POINT RATHER THAN DUPLICATION.** Refusing a
 * 40 MB video in the browser saves an officer on a village connection the upload; the server
 * refuses it again because a client check is not a bound. The wording is the server's so the two
 * doors cannot tell a person two different stories about one file.
 */
export function artisanUploadRefusal(file: File): string | null {
  const name = file.name.toLowerCase();
  if (!/\.(xlsx|xlsm|xltx)$/.test(name)) {
    return `'${file.name}' is not an Excel workbook. Fill in the artisan list pro-forma and upload that, or use File > Save As and choose 'Excel Workbook (.xlsx)'.`;
  }
  if (file.size > MAX_ARTISAN_UPLOAD_BYTES) {
    return "That artisan list is over the 4 MB limit; send a smaller file.";
  }
  if (file.size === 0) {
    return "The upload was empty. Attach the filled-in artisan list pro-forma.";
  }
  return null;
}
