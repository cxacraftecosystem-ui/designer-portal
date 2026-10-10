/**
 * THE FIFTH SCOPE, CLIENT-SIDE: which design & prototype workshops an INSPECTOR may READ.
 *
 * `backend/app/services/design_workshop_inspectors.py` is the argument in full and this file is the
 * wire; what follows is the part a caller on this side has to hold in their head, because every one
 * of these facts is a place a reasonable instinct gives the wrong answer.
 *
 * ── 1. WHO MAY INSPECT IS A SET, NOT A RANK FLOOR — AND SINCE 2026-10-09 IT HAS FOUR MEMBERS ──
 *
 * The owner's ruling D3 lets a Ministry Admin, an admin and the master admin be APPOINTED to inspect
 * a workshop, beside the Inspector / Reviewer tier, so the door to this surface is
 * `INSPECTION_HOLDER_ROLES` on the server and {@link canInspectDesignWorkshops} here. It is still
 * not "INSPECTOR and above": a professor, an Assistant Director and a Regional Director outrank an
 * inspector and are refused. `INSPECTION_ROLES` stays the one-member TIER, which now decides only
 * whose daily work this is — see `isInspectorTier`.
 *
 * AN ADMIN WAS REFUSED HERE BY NAME BEFORE THE RULING, on the argument that an admin scoped by their
 * own inspection rows sees an empty list and reads it as a broken feature. The rows are an admin's
 * to hold now, so the list is theirs; an empty one is answered with a sentence
 * ({@link inspectionEmptyState}), and a 403 met by an admin means the same thing
 * ({@link inspectionRefusalMeansNoPosts}). The list is still scoped to the reader's own rows, never
 * to "everything, because they are an admin" — that half is the server's to keep.
 *
 * WHICH workshop an account may inspect is decided per workshop, on the server, and answered with a
 * sentence: nobody appoints themselves, nobody inspects what they authored, nobody inspects a
 * workshop they also supervise, and an inspector's writes to the workshop they inspect are refused
 * (a 403 naming the post) — through the admin routes as well.
 *
 * ── 2. AN INSPECTOR IS OUTSIDE `DESIGN_WORKSHOP_ROLES`, SO NO OTHER ROUTE ANSWERS THEM ─────────
 *
 * `load_workshop_or_404` refuses anybody outside that frozenset before it looks at anything, which
 * is why PROFESSOR cannot open a design workshop either. Everything an inspector holds comes from a
 * `DesignWorkshopInspector` row and from this prefix. Practically, for a screen author:
 *
 *   * `/design-workshops/{id}` and every page beneath it — stages, photos, report, readiness,
 *     custom sections, AI layers, codes, provenance, sketches — is a **404** for an inspector.
 *     {@link DESIGN_WORKSHOP_DESTINATIONS} is that list, and {@link inspectionMayOpen} is the
 *     predicate a renderer asks before drawing any of them.
 *   * `GET /design-workshops/{id}/custom-sections` is one of those, so this client can read a
 *     stage's `custom` answers and **cannot read the questions they answer**. Say so on screen; do
 *     not draw the keys and call them labels.
 *   * `GET /design-workshops/schema` is the exception and takes `get_current_user` with no role
 *     dependency, so the 496-field registry — which is what names the stages and their fields — is
 *     readable by an inspector like anybody else.
 *
 * AN ADMINISTERING TIER APPOINTED TO INSPECT IS INSIDE `DESIGN_WORKSHOP_ROLES`, and is the one
 * reader of this surface for whom the workshop tree does open — read-only on the workshop they
 * inspect, whose writes answer a 403 naming the post. This surface still offers none of those
 * pages: it is the inspection's read, and {@link inspectionMayOpen} answers for the payload, not
 * for the reader.
 *
 * ── 3. `readOnly: true` IS ON THE WIRE ON PURPOSE ─────────────────────────────────────────────
 *
 * The route's own comment says why: both clients will eventually render this payload through the
 * same screen as the designer's read, and a screen that cannot tell the two apart offers a Save
 * button the API answers 404 to. {@link inspectionIsReadOnly} is where that is honoured, and its
 * docstring carries the one subtlety — an ABSENT flag means read-only here, which is the opposite
 * of how `truncated` is treated two files over.
 *
 * ── 4. WHAT THE READ DELIBERATELY DOES NOT CARRY ──────────────────────────────────────────────
 *
 * `transcripts` is absent: the co-designer media predicate is never asked on this route, so widening
 * it can never widen this surface by accident. **The workshop's files are a second read instead**
 * (owner's ruling, sweep item F5, 2026-10-10): `GET /design-workshop-inspections/{id}/media`, behind
 * the same loader, signed and read-only — `lib/workshopReaderMedia.ts` fetches it and
 * `components/designworkshop/ReaderWorkshopMedia.tsx` draws it. The workshop's own questions DO
 * travel on this read, as `customSections`. `dictationConsentByName` is absent for the plainer
 * reason that this read does not resolve it.
 *
 * Provenance names ARE resolved, because "who wrote this field" is most of what an inspection is
 * for. That is the one thing this payload has that the paged list does not.
 *
 * ── 5. THE PUT REPLACES THE WHOLE SET ─────────────────────────────────────────────────────────
 *
 * As with viewers: there is no add route and no remove route, so a caller that posts only what it
 * just ticked has silently ended everybody else's inspection. {@link putDesignWorkshopInspectors}
 * takes the whole list and is named for it. Two differences from the viewers wire, and both change
 * what a screen may say:
 *
 *   * **There is no creator held quietly off to one side.** Nobody holds an inspection by any route
 *     other than a row in this table, so an empty answer means NOBODY IS INSPECTING THIS WORKSHOP —
 *     the literal truth, and a screen may print it as such.
 *   * **The workshop's own people are refused BY NAME.** Anybody who worked on it — a co-designer
 *     holding a `DesignWorkshopViewer` row, somebody who wrote its stages — is refused, because an
 *     independent review by somebody who worked on it is not a review. So are the person saving
 *     (nobody appoints themselves) and the workshop's Assistant Director or Regional Director. Since
 *     the owner's ruling of 2026-10-09 these refusals are reachable every day rather than only
 *     through a promotion — the administering tiers both appoint and may be appointed — and the
 *     server answers each with a 409 whose sentence names the rule broken. Surface that sentence;
 *     do not pre-empt it with a client-side copy of a per-workshop rule this side can only guess at.
 */

import { ApiError, apiFetch, buildQuery } from "@/lib/api";
import {
  geoValue,
  inputValue,
  isFilled,
  listValue,
  referenceDisplayHint,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwRegistry,
  type DwStageCompleteness,
  type DwStageData,
  type DwSummary,
  type DwValue
} from "@/lib/designWorkshops";
import type { DwCustomDefinition } from "@/lib/customSections";
import { isUnreachable } from "@/lib/offline";
import { canInspectDesignWorkshops, isInspectorTier } from "@/lib/permissions";
import { richSummary } from "@/lib/richText";
import type { PageResult, User, UserRole } from "@/lib/types";

/* ────────────────────────────────────────────────────────────────────────────
 * The admin's two lists
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One account assigned to inspect a workshop, as `GET /{workshop_id}/inspectors` returns it.
 *
 * `assignedAt` AND NOT `grantedAt`, which is the sibling's name for the same column shape. Nothing
 * was granted to anybody: an admin assigned an examiner to a piece of work, which is why the column
 * beside it on the server is `assignedById`. Copying the viewers' noun here would quietly re-file an
 * assignment as a permission grant on the one screen whose whole subject is the difference.
 *
 * `name`, `email` and `role` travel WITH the row rather than being joined against a directory the
 * screen also holds — an inspector whose account has since been barred is precisely the row an admin
 * most needs to see and act on, and a join against the eligible list would draw it as a bare cuid.
 *
 * Null-tolerant on the timestamp for the same reason the viewers row is: an older row may carry no
 * `createdAt`, and "assigned at a moment nobody recorded" is not a reason to hide the assignment.
 */
export type DwInspector = {
  userId: string;
  name: string;
  email: string;
  role: UserRole | string;
  assignedAt?: string | null;
};

export type DwInspectorList = { inspectors: DwInspector[] };

/**
 * One account `GET /eligible-inspectors` offers.
 *
 * Deliberately NOT `User`: the endpoint returns the four fields needed to name somebody in a picker
 * and nothing else, because the caller is choosing an examiner and has no business receiving the
 * capability flags or the auth provider.
 *
 * `role` IS RENDERED, AND SINCE 2026-10-09 IT IS ONE OF FOUR. It used to be the Inspector / Reviewer
 * tier on every row, and this type said it would be typed as a role anyway "because the day the
 * owner adds a second member to that frozenset this picker must show which of the two an account
 * is". That day came: the eligible clause is the holder set — the tier plus a Ministry Admin, an
 * admin and the master admin — and the picker names each row's role with no change here.
 */
export type DwEligibleInspector = {
  id: string;
  name: string;
  email: string;
  role: UserRole | string;
};

/**
 * One page of eligible accounts, and whether it is the whole answer.
 *
 * `truncated` is the server's own word, reported under the same name by the viewers picker and by
 * the reference picker, deliberately, so both clients already know it. Unlike the viewers' flag it
 * covers exactly ONE cut here — the account list stopping at `ELIGIBLE_INSPECTOR_LIMIT` — because
 * there is no second roster read to be truncated: an inspector is not empanelled, so `DesignerRoster`
 * is never consulted. That is why {@link eligibleInspectorNotice} holds three states where its
 * sibling holds four, and the missing one is not an oversight to be restored.
 *
 * Typed as required because the endpoint always sends it. `apiFetch` is a plain cast rather than a
 * schema parse, so a deployment that predates the field puts `undefined` here at runtime — hence the
 * `Boolean(...)` at the one place that reads it. The safe default is the quiet one: an unknown flag
 * says nothing on screen rather than crying truncation at a list that is complete.
 */
export type DwEligibleInspectorList = { users: DwEligibleInspector[]; truncated: boolean };

/**
 * Longest `search` the two search endpoints accept (`Query(None, max_length=120)` on both); past it
 * the answer is a 422.
 *
 * Exported so the boxes an admin and an inspector type into can cap themselves at the server's own
 * number, which makes the refusal unreachable rather than handled. A picker that answers a long
 * paste with a red validation banner has taught nobody anything they can act on.
 */
export const ELIGIBLE_INSPECTOR_SEARCH_MAX = 120;

/**
 * How many inspectors one workshop may be assigned in a single call —
 * `MAX_DESIGN_WORKSHOP_INSPECTORS` on the server, and the `max_length` of `userIds` on the body.
 *
 * TWENTY-FIVE, which is lower than the viewers' hundred because the two are different quantities:
 * that list holds a field TEAM and this one holds examiners. An inspection panel is one person,
 * occasionally two, so this is not a limit anybody meets by working — it is here so that the
 * validation, which reads every named account out of the user table before it writes anything, has
 * a bounded cost the caller cannot choose.
 *
 * Mirrored rather than discovered: a picker that lets an admin tick a twenty-sixth name and then
 * shows them a 422 has spent their afternoon to teach them a number this file already knew.
 */
export const MAX_DESIGN_WORKSHOP_INSPECTORS = 25;

/**
 * Everyone assigned to inspect this workshop. A Ministry Admin, an admin or the master admin,
 * server-side (`require_workshop_assigner`).
 */
export function listDesignWorkshopInspectors(workshopId: string) {
  return apiFetch<DwInspectorList>(`/design-workshop-inspections/${workshopId}/inspectors`);
}

/**
 * REPLACE the inspection set with exactly `userIds`, and answer with it as it now stands.
 *
 * Named `put…` and typed to take the whole list because that is what the endpoint means — see point
 * 5 in the file header. The answer is adopted as the new baseline rather than assumed to equal what
 * was sent: two admins on the same screen must not each end up believing their own payload was the
 * outcome, and the server is entitled to hold a row this client never knew about.
 *
 * Idempotent: saving an unchanged screen writes nothing and does not restamp `assignedAt`, which
 * matters because that timestamp is the only answer anybody has to "how long has this workshop been
 * under inspection".
 */
export function putDesignWorkshopInspectors(workshopId: string, userIds: string[]) {
  return apiFetch<DwInspectorList>(`/design-workshop-inspections/${workshopId}/inspectors`, {
    method: "PUT",
    body: JSON.stringify({ userIds })
  });
}

/**
 * The accounts that may be assigned an inspection at all — the holder set, minus the account asking
 * (nobody appoints themselves). A Ministry Admin, an admin or the master admin, server-side.
 *
 * ONE ROLE SET AND ONE ROSTER, which is the whole difference from `eligible-viewers`: that endpoint
 * reads two rosters because it offers DESIGNERs, whose empanelment gates their sign-in, while an
 * inspection is held by role and by appointment with no empanelment behind it. The platform
 * allow-list still applies — an account it has rejected or suspended cannot sign in, so offering it
 * here would mean an admin assigning an inspection that the next sign-in refuses with nothing on
 * screen saying why.
 *
 * NOT FILTERED AGAIN HERE BY ROLE. Who may inspect THIS workshop is a per-workshop question the
 * server answers on save, with a sentence; this list is who may inspect at all.
 *
 * **THE SEARCH IS THE SERVER'S**, folded into the same `WHERE` as the eligibility rule, so it reaches
 * accounts past the ceiling. Narrowing the array this function returns would not, because that array
 * is what the ceiling already cut — see §11.5 of the frontend skill for why a client-side filter over
 * a server-truncated list is the wrong search box and looks exactly like the right one. Omitted, blank
 * and whitespace-only are one case: `buildQuery` drops an empty string exactly as it drops `undefined`.
 *
 * The caller is expected to DEBOUNCE this. `contains` is an `ILIKE '%term%'` over `User` that no index
 * can answer, so every keystroke that escapes a debounce is a full scan of the largest table here.
 *
 * **THE ORDER IS THE SERVER'S AND MUST NOT BE RE-SORTED.** It is `name` ascending then `id`, a TOTAL
 * order, and the id is not decoration: without a tiebreaker which accounts fall inside the ceiling is
 * Postgres's arbitrary choice and can differ between two identical requests, so "who is hidden" would
 * change on refresh. Re-sorting in the browser would also disagree with Postgres's collation on
 * exactly the names this repository is full of.
 */
export function listEligibleDesignWorkshopInspectors(search?: string) {
  return apiFetch<DwEligibleInspectorList>(
    `/design-workshop-inspections/eligible-inspectors${buildQuery({ search: search?.trim() ?? "" })}`
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * The inspector's own surface — every route below is a GET, and that is the feature
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One workshop under inspection: the header every list row carries, plus the stages, plus the scores.
 *
 * SPELLED OUT RATHER THAN `Omit<DwDetail, …>`, because the differences from the designer's read are
 * the point rather than an omission and a subtraction expression hides them. `transcripts` and
 * `dictationConsentByName` are the two keys `DwDetail` has and this does not; see point 4 of the file
 * header for the decision behind the first, which is the one that changes what a screen may draw.
 */
export type DwInspectionDetail = DwSummary & {
  /** Keyed by stage key, exactly as the designer's read groups them — same serialiser, not a copy. */
  stages: Record<string, DwStageData>;
  completeness: Record<string, DwStageCompleteness>;
  schemaVersion: string;
  /** See `DwDetail.customSchemaVersion` — a second string, never folded into the one above. */
  customSchemaVersion?: string;
  /**
   * The workshop's own questions, in the shape `GET /design-workshops/{id}/custom-sections` serves.
   * Optional because an older API sends none; the answers are then drawn without their wording.
   */
  customSections?: DwCustomDefinition;
  /**
   * The server's own word for "this is a read". See {@link inspectionIsReadOnly}, which is the only
   * thing that should ever read this key directly.
   */
  readOnly?: boolean;
};

export type DwInspectableListParams = {
  page?: number;
  pageSize?: number;
  search?: string | null;
  /** A `DesignWorkshopStatus` token. Empty or absent means any status. */
  statusFilter?: string | null;
  /** The submission round — 0 for a report never handed in. Absent means any round. */
  round?: number | null;
  /** A state or union territory, exactly as the address list spells it. */
  state?: string | null;
  /** A `WORKSHOP_KIND` token. */
  workshopKind?: string | null;
  /** The first and last day (YYYY-MM-DD, inclusive) the workshop may have STARTED on. */
  dateFrom?: string | null;
  dateTo?: string | null;
};

/** The filters a reader has set, as the query the list read takes. Blank values are left out. */
export function inspectableListQuery(params: DwInspectableListParams): string {
  const text = (value: string | null | undefined) => (value && value.trim() ? value.trim() : undefined);
  return buildQuery({
    page: params.page,
    pageSize: params.pageSize,
    search: text(params.search),
    statusFilter: text(params.statusFilter),
    round: typeof params.round === "number" && Number.isInteger(params.round) && params.round >= 0 ? params.round : undefined,
    state: text(params.state),
    workshopKind: text(params.workshopKind),
    dateFrom: text(params.dateFrom),
    dateTo: text(params.dateTo)
  });
}

/** How many filters other than the search box are set — for the "Clear filters" control. */
export function inspectableFilterCount(params: DwInspectableListParams): number {
  return [
    params.statusFilter,
    typeof params.round === "number" ? String(params.round) : "",
    params.state,
    params.workshopKind,
    params.dateFrom,
    params.dateTo
  ].filter((value) => typeof value === "string" && value.trim() !== "").length;
}

/**
 * The design & prototype workshops this inspector has been assigned, newest first.
 *
 * **AN INSPECTOR WITH NO INSPECTION ROW SEES AN EMPTY PAGE, AND THAT IS THE WHOLE SCOPE.** There is
 * no "all workshops" arm, no rank fallback and no `createdById` arm — an inspector creates nothing —
 * so this list has exactly one source. A caller must be able to tell that empty page apart from a
 * failed load, which is why every screen over this holds `items === null` and `items === []` as two
 * different states.
 *
 * THE LIST IS HALF THE FEATURE. A scope the list does not honour tells its holder that a workshop
 * exists (they can open it by id) and simultaneously that it does not (it is absent from every list
 * they can reach), and nothing in either client navigates to a workshop by typed id.
 *
 * THE FILTERS NARROW THE INSPECTOR'S OWN ROWS AND NEVER WIDEN THEM (sweep item F13):
 * status, submission round, state, type of workshop and the days it started on are AND-composed with
 * the inspection scope on the server, so no value of any of them can list a workshop the account was
 * not appointed to. There is no `mineOnly` and no `deletedOnly`: "mine" is the only scope there is,
 * and a soft-deleted workshop is a 404 for everyone but an admin.
 */
export function listInspectableDesignWorkshops(params: DwInspectableListParams) {
  return apiFetch<PageResult<DwSummary>>(`/design-workshop-inspections${inspectableListQuery(params)}`);
}

/** One workshop under inspection, read-only, every stage and its completeness. */
export function getWorkshopUnderInspection(workshopId: string) {
  return apiFetch<DwInspectionDetail>(`/design-workshop-inspections/${workshopId}`);
}

/* ────────────────────────────────────────────────────────────────────────────
 * Honouring `readOnly`
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * IS THIS PAYLOAD A READ? Absent means yes.
 *
 * **THE ABSENT CASE IS THE WHOLE FUNCTION**, and it is the opposite of how `truncated` is treated in
 * `designWorkshopViewers`. There, an unknown flag must say nothing rather than cry truncation at a
 * complete list, so absence falls to the quiet side. Here the quiet side is the DANGEROUS one: a
 * deployment that predates the key would hand a screen a payload with no flag, and a `=== true` test
 * would then draw a Save button on a prefix that has no write route at all — the exact bug the
 * boolean was put on the wire to prevent. So absence fails CLOSED.
 *
 * `?? true` and not a hardcoded `true`: an explicit `false` is honoured, because the day this screen
 * is shared with the designer's read that is the value that will arrive from the other route, and a
 * function that ignored the flag would have to be found and rewritten by somebody who did not know
 * it existed. **That day this predicate gains a second argument naming which route the payload came
 * from** — the designer's read carries no `readOnly` key either, and absence cannot mean two things.
 * This is the one place that change goes.
 */
export function inspectionIsReadOnly(detail: { readOnly?: boolean } | null | undefined): boolean {
  return detail?.readOnly ?? true;
}

/**
 * Every page the DESIGNER'S workshop hub offers, named rather than remembered.
 *
 * Each of these is a real route under `/design-workshops/{id}`, and each answers a 404 to an
 * inspector because `load_workshop_or_404` refuses anybody outside `DESIGN_WORKSHOP_ROLES` before it
 * looks at the row. They are listed so that a renderer asks {@link inspectionMayOpen} once rather
 * than nine screen authors reasoning it out again — and so that a page ADDED to the hub is one entry
 * away from being correctly refused here, instead of shipping as a link that 404s.
 *
 * `"stages"` is the load-bearing one: the inspection view draws a heading per stage, and the obvious
 * thing to make each heading is a link to the page a designer edits it on.
 */
export const DESIGN_WORKSHOP_DESTINATIONS = [
  "stages",
  "photos",
  "report",
  "readiness",
  "custom-sections",
  "ai-layers",
  "codes",
  "provenance",
  "sketches-and-prototypes"
] as const;

export type DesignWorkshopDestination = (typeof DESIGN_WORKSHOP_DESTINATIONS)[number];

/**
 * May a screen over this payload offer that destination? On a read, never.
 *
 * A function rather than a constant `false` so that the CALL SITE reads as a question about the
 * payload it holds — and so that the day `readOnly` can be false the answer changes in one place
 * rather than in nine JSX branches nobody can enumerate. The `destination` argument is unused today
 * and is not decoration: it is what makes the call site name which link it is about to draw, and it
 * is what `frontend/e2e/design-workshop-inspections-unit.spec.ts` walks —
 * {@link DESIGN_WORKSHOP_DESTINATIONS} in full, asserting every one is refused, which is what stops
 * a tenth destination being added to the hub and quietly linked from here.
 */
export function inspectionMayOpen(
  destination: DesignWorkshopDestination,
  detail: { readOnly?: boolean } | null | undefined
): boolean {
  // Named so the parameter is visibly consumed rather than silently ignored; the answer does not
  // depend on WHICH destination it is, because the refusal is `load_workshop_or_404` and that helper
  // stands in front of all nine identically.
  void destination;
  return !inspectionIsReadOnly(detail);
}

/* ────────────────────────────────────────────────────────────────────────────
 * Reading a failure honestly
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Is this failure "the server has no such route" rather than "no such workshop"?
 *
 * Only ever asked of {@link listEligibleDesignWorkshopInspectors}, and only that call, because it is
 * the one request in the family that carries no id: a 404 from it cannot mean a missing record and
 * therefore means a missing ROUTE. Asking the same question of `/{id}/inspectors` would be
 * unanswerable — a 404 there is genuinely either — which is why the probe is pinned to the id-less
 * endpoint instead of being a general helper.
 *
 * A 404 FROM THIS FAMILY MEANS THE DEPLOYMENT PREDATES THE FEATURE, and that is a real state rather
 * than a hypothetical: the routes are in the tree, but this repository ships the browser bundle and
 * the API separately, and `/eligible-inspectors` is matched against `/{workshop_id}` on a server
 * without the route — which answers 404 "Record not found". The panel says which rather than
 * rendering a dead form.
 */
export function inspectionAdministrationMissing(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

/**
 * What a failed or refused request on the inspectors panel says, told apart in words. It lives here
 * rather than in `DesignWorkshopInspectorsPanel` so the decision can be exercised without a renderer.
 *
 * `isUnreachable` and not `isTransient`: the latter answers "is it worth retrying" and counts every
 * 5xx as yes, so a repository that ANSWERED and then failed would be reported as a connection
 * problem — which sends an admin to look at their signal and leaves a real fault wearing an offline
 * message.
 *
 * THE 422 AND THE 409 ARE PASSED THROUGH ALMOST BARE, and that is the important arm rather than a
 * fallback. The server's refusals on these routes name the account, say what is wrong with it and
 * say where the remedy is — "clear that on the access screen first", "take them off the workshop's
 * viewers first" — and a separation-of-duties refusal (the reader naming themselves, somebody who
 * authored or supervises the workshop) is a 409 whose sentence names the rule broken. Paraphrasing
 * any of that would replace a sentence an admin can act on with one they cannot.
 *
 * THE 403'S ROLE CLAUSE IS ONLY FOR A READER THE ROLE COULD BE REFUSING. An account that may assign
 * and is refused anyway has met a rule about THIS workshop — a post it holds there, say — which the
 * server's sentence names; telling it that choosing inspectors "is administration" would point it at
 * a door it already holds.
 */
export function inspectorAdministrationFailure(
  error: unknown,
  fallback: string,
  readerMayAssign: boolean
): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "This device cannot reach the repository, so nothing was sent and nothing has changed. Check the connection and try again.";
  }
  if (error.status === 403) {
    return readerMayAssign
      ? `The repository refused this. ${error.message}`
      : `The repository refused this. ${error.message} Choosing who inspects a workshop is administration, so it is open to a Ministry Admin, an admin and the master admin.`;
  }
  if (error.status === 409 || error.status === 422) {
    return `The repository would not accept this. ${error.message}`;
  }
  if (error.status === 404) {
    return `${error.message} This workshop may have been deleted since the list was loaded — reload the page to see the current list.`;
  }
  return error.message || fallback;
}

/**
 * DOES THIS REFUSAL OF THE INSPECTOR'S LIST MEAN "YOU HOLD NO INSPECTION POSTS"?
 *
 * True only for a 403 met by an account that may be appointed to inspect WITHOUT being of the
 * Inspector / Reviewer tier — one of the three administering tiers. Every server that can answer
 * them so says the same true thing: a server that admits them only once they hold a row, and a
 * server older than the owner's ruling of 2026-10-09, which refused them by name because they could
 * hold none. So the page draws the empty state ({@link inspectionEmptyState}) rather than a red
 * banner, which on a screen whose only content is the reader's own rows reads as a broken
 * deployment.
 *
 * AN INSPECTOR / REVIEWER REFUSED IS A FAULT, AND KEEPS THE BANNER. The surface is that tier's by
 * role, so a 403 there means something changed under the session — a role moved, say — and saying
 * "you hold no posts" would hide it.
 */
export function inspectionRefusalMeansNoPosts(error: unknown, user: User | null | undefined): boolean {
  return (
    error instanceof ApiError &&
    error.status === 403 &&
    canInspectDesignWorkshops(user) &&
    !isInspectorTier(user)
  );
}

/**
 * What the inspector's list says when it holds nothing — an answer, never a failure.
 *
 * "You do not hold any inspection posts" for everybody, the tier included: since admins may be
 * appointed too, "No workshop is assigned to you" stopped being the sentence that fits every reader,
 * and one sentence for one state is what lets a reader trust it. It names who appoints and where, so
 * an empty page is a next move rather than a dead end.
 */
/** The status filter's choices — every status a workshop can hold, "any" first. */
export const INSPECTION_STATUS_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: "", label: "Any status" },
  { value: "DRAFT", label: "Draft" },
  { value: "IN_PROGRESS", label: "In progress" },
  { value: "COMPLETE", label: "Complete" },
  { value: "PRE_SUBMISSION", label: "Pre-submission" },
  { value: "NEEDS_REVISION", label: "Needs revision" },
  { value: "SUBMITTED", label: "Submitted" },
  { value: "APPROVED", label: "Approved" },
  { value: "ARCHIVED", label: "Archived" }
];

/**
 * The submission-round filter's choices. Round 0 is a report nobody has handed in yet; a report
 * goes through a handful of rounds, so nine are offered.
 */
export const INSPECTION_ROUND_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: "", label: "Any round" },
  { value: "0", label: "Not handed in yet" },
  ...Array.from({ length: 9 }, (_, index) => ({ value: String(index + 1), label: `Round ${index + 1}` }))
];

export function inspectionEmptyState(searched: boolean): { title: string; body: string } {
  return searched
    ? {
        title: "No workshop you inspect matches that search and those filters",
        body: "This searches only the workshops you have been appointed to inspect, which is the whole of what you can read here. Clear the search and the filters to see them all."
      }
    : {
        title: "You do not hold any inspection posts",
        body: "A Ministry Admin, an admin or the master admin appoints a workshop's inspectors one workshop at a time, on Workshop oversight. Until somebody appoints you there is nothing here to read — this page is not hiding anything from you, and nothing failed to load."
      };
}

/**
 * THE ONE SENTENCE UNDER THE ADMIN'S SEARCH BOX, or "" when the screen must say nothing.
 *
 * Silence is the common answer and the correct one: a complete list has nothing to explain, and a
 * standing note about pagination on every visit is padding these screens have been asked for less of.
 *
 * **A FUNCTION RATHER THAN A TERNARY IN THE PANEL**, for the reason `eligibleViewerNotice` gives: a
 * decision buried in JSX is only ever exercised by somebody looking at a screen, and there is no
 * React renderer in this project's devDependencies to exercise it any other way.
 *
 * **THREE STATES, WHERE THE VIEWERS' NOTICE HOLDS FOUR, AND THE MISSING ONE IS DELIBERATE.** That
 * function's first case is `truncated` with an EMPTY list — the state where the active-roster read
 * was cut, so eligible designers are absent from every possible search and no narrowing can reach
 * them. `eligible_inspectors` reads no roster at all, so `truncated` here can only mean the account
 * list hit its ceiling, and a ceiling is always reachable by typing. Copying the fourth sentence
 * across would print advice about a cut that cannot happen.
 *
 * @param truncated the server's `truncated` — coerced by the caller, since an older deployment omits it
 * @param offered how many accounts the current answer holds
 * @param searched was a term actually sent (a blank box sends nothing and is not a search)
 */
export function eligibleInspectorNotice({
  truncated,
  offered,
  searched
}: {
  truncated: boolean;
  offered: number;
  searched: boolean;
}): string {
  if (truncated && !searched) {
    return "Too many accounts to show them all — search a name or email to reach the rest.";
  }
  if (truncated) return "Too many matches to show them all — narrow the search.";
  // "AN ACCOUNT THAT MAY INSPECT" AND NO LONGER "AN INSPECTOR / REVIEWER ACCOUNT": since 2026-10-09
  // the list also holds the Ministry Admin, admin and master admin accounts, and a sentence naming
  // only the tier would tell somebody searching for an admin by name that admins are not offered.
  // The handset says the same sentence (`dwInspectorOfferNotice` in Android's
  // `data/DesignWorkshopInspections.kt`, all three of these word for word): the server sends the
  // widened list to its admin-only inspectors screen too, so the tier-only wording was false there.
  if (searched && offered === 0) return "No account that may inspect matches that search.";
  return "";
}

/* ────────────────────────────────────────────────────────────────────────────
 * Reading one stored answer, for a surface that cannot edit it
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What one field of one row has to say on a read.
 *
 * THREE ANSWERS AND NOT ONE STRING, because the third is a different FACT and collapsing it into a
 * sentence would put a lie in the same slot as a value. `media` is not "this field is empty" and it
 * is not "here is the photograph": it is "this field holds N files", which the screen resolves
 * against the workshop's own files (its surface's media read) and draws beside the field's label.
 */
export type InspectionFieldReading =
  | { kind: "text"; text: string }
  | { kind: "empty" }
  | { kind: "media"; count: number };

/** The registry types whose value is a media id — the ones an inspection read cannot resolve. */
const MEDIA_FIELD_TYPES = new Set(["IMAGE", "IMAGE_LIST", "FILE", "AUDIO", "VIDEO"]);

/** One ENUM token as a human sees it, falling back to the raw token rather than dropping it. */
function optionLabel(registry: DwRegistry, field: DwField, token: string): string {
  const options = field.options ?? (field.enum ? registry.enums[field.enum] : undefined) ?? [];
  const match = options.find((option) => option.value === token);
  // A token with no option left in the registry is NOT dropped. It is a real answer a designer gave
  // against a list that has since changed, and hiding it from an INSPECTION is the one place that
  // would be least forgivable: an inspector reads this to check what was recorded. The raw token is
  // the only name the answer still has, so it is printed.
  return match ? match.label : token;
}

/**
 * ONE STORED ANSWER, AS AN INSPECTOR SHOULD SEE IT.
 *
 * ── WHY THIS EXISTS RATHER THAN MOUNTING `FieldInput` DISABLED ────────────────────────────────
 *
 * The obvious reuse is the designer's own control with `disabled` passed down, and it was refused
 * for three reasons rather than for tidiness:
 *
 *  1. **It draws controls that would 404.** `FieldInput` mounts `MediaCaptureField`, the reference
 *     picker, the dictation button and `StageRecordEmbed` (which mounts a whole record form). Every
 *     one of those reaches a route an inspector is refused, and the brief for this surface is that
 *     it must never draw a control the API answers 404 to. A disabled upload button is still an
 *     upload button on a screen whose entire premise is that nothing here can be written.
 *  2. **It resolves media through the wrong door.** `GET /media/{id}` is entitled per file and an
 *     inspector holds no upload, no grant and no viewer row there, so every image tile would render
 *     its "could not be read" state. A reader's files come from its own surface's media read instead
 *     — see `kind: "media"` and `components/designworkshop/ReaderWorkshopMedia.tsx`.
 *  3. **It needs `onChange`, `onPatch` and a workshop it may write to.** Faking those is how a
 *     read-only surface acquires a write path by accident.
 *
 * ── WHAT IT DOES REUSE, WHICH IS ALL THE PARTS THAT INTERPRET A VALUE ─────────────────────────
 *
 * `inputValue`, `listValue`, `geoValue`, `isFilled`, `richSummary` and `referenceDisplayHint` are
 * the same functions the stage form and the offline search index read a value through, so an
 * inspector and the designer who typed it are reading one interpretation of the bytes. Nothing here
 * re-decides what a MONEY value is, what counts as filled, or what a REF stands for.
 *
 * `entity` and `row` are taken rather than just the value because `referenceDisplayHint` resolves a
 * reference from the SIBLING keys hydration wrote onto the same row — the artisan's name is on the
 * row beside the id — which is the only way to name a linked record without a lookup this surface
 * cannot make.
 */
export function inspectionFieldReading(
  registry: DwRegistry,
  entity: DwEntity,
  field: DwField,
  row: DwEntryData
): InspectionFieldReading {
  const value: DwValue | undefined = row[field.key];

  if (MEDIA_FIELD_TYPES.has(field.type)) {
    // Counted BEFORE the filled test, because "no photographs" and "photographs this read does not
    // carry" are the two states this whole branch exists to keep apart.
    const count = Array.isArray(value) ? listValue(value).length : isFilled(value) ? 1 : 0;
    return count > 0 ? { kind: "media", count } : { kind: "empty" };
  }

  if (!isFilled(value)) return { kind: "empty" };

  const withUnit = (text: string) => (field.unit ? `${text} ${field.unit}` : text);

  switch (field.type) {
    case "RICH_TEXT":
      // A generous limit rather than the 160 a row TITLE uses: this is the narrative an inspection
      // is largely about, and truncating it to a title's length would hide the paragraph being
      // inspected. Still bounded — `richSummary` appends an ellipsis when it cuts, which is the only
      // signal there is that more was written, so do not strip it at the call site.
      return { kind: "text", text: richSummary(value, 2000) };
    case "BOOL":
      return { kind: "text", text: value === true || inputValue(value) === "true" ? "Yes" : "No" };
    case "ENUM":
      return { kind: "text", text: optionLabel(registry, field, inputValue(value).trim()) };
    case "MULTI_ENUM":
      return {
        kind: "text",
        text: listValue(value)
          .map((token) => optionLabel(registry, field, token))
          .join(" · ")
      };
    case "TAGS":
      return { kind: "text", text: listValue(value).join(" · ") };
    case "GEO": {
      const point = geoValue(value);
      if (!point) return { kind: "empty" };
      // Six decimals is about 10 cm — more than a handset fix is worth and enough that two villages
      // are never one number. The accuracy is printed when the device reported it, because a fix
      // with a 2 km radius and one with a 5 m radius are different evidence.
      const at = `${point.lat.toFixed(6)}, ${point.lon.toFixed(6)}`;
      return {
        kind: "text",
        text: Number.isFinite(point.accuracy) ? `${at} (±${Math.round(point.accuracy as number)} m)` : at
      };
    }
    case "REF": {
      const named = referenceDisplayHint(entity, field, row).trim();
      // NEVER the raw id as a fallback. A cuid asks an inspector to recognise a record they cannot
      // possibly recognise, and on this surface there is no picker to open and check it against.
      return named ? { kind: "text", text: named } : { kind: "text", text: "A linked record this read cannot name" };
    }
    default: {
      const text = inputValue(value).trim();
      return text ? { kind: "text", text: withUnit(text) } : { kind: "empty" };
    }
  }
}
