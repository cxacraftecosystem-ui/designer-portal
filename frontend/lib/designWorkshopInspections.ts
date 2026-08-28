/**
 * THE FIFTH SCOPE, CLIENT-SIDE: which design & prototype workshops an INSPECTOR may READ.
 *
 * `backend/app/services/design_workshop_inspectors.py` is the argument in full and this file is the
 * wire; what follows is the part a caller on this side has to hold in their head, because every one
 * of these facts is a place a reasonable instinct gives the wrong answer.
 *
 * ── 1. THE TIER IS A SET, NOT A RANK FLOOR, AND THE SET HAS ONE MEMBER ─────────────────────────
 *
 * `INSPECTION_ROLES` is `frozenset({"INSPECTOR"})`, and `assert_inspection_surface` answers **403
 * to an admin as well** — deliberately, in its own words: scoped by their own inspection rows an
 * admin sees an empty list and reads it as a broken feature, and scoped by "everything, because
 * they are an admin" this surface silently becomes a second full read of every workshop in the
 * repository. So the mirror of that door is {@link canInspectDesignWorkshops} in `lib/permissions`,
 * which is set membership on INSPECTOR alone. **It is not "INSPECTOR and above".** A rank floor
 * would offer the menu entry and the URL to every admin and master admin in the repository and land
 * all of them on a 403.
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
 * `transcripts` is absent, and it is an absence with a decision behind it rather than an oversight:
 * an inspector holds no `DataAccessGrant`, no upload of their own and no viewer row, so the media
 * predicate would cost a query to produce an empty list — and would put this route on the media
 * path at all, where the next person widening that predicate would widen this surface without
 * noticing. **Whether an inspector should see the photographs is an owner's decision that has not
 * been made**, so today the answer is no, stated once. A screen over this payload must therefore
 * say that media are not part of an inspection read rather than draw an empty gallery or a broken
 * frame — the two look identical and only one of them is true. `dictationConsentByName` is absent
 * for the plainer reason that this read does not resolve it.
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
 *   * **The workshop's own people are refused BY NAME.** The creator and any co-designer holding a
 *     `DesignWorkshopViewer` row are refused with a 422 saying an independent review by somebody who
 *     worked on it is not a review. That refusal exists nowhere else in this codebase and it is the
 *     point of the tier; surface the server's sentence rather than pre-empting it, because the two
 *     role sets are disjoint today and the case is reachable only through a promotion.
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
import { richSummary } from "@/lib/richText";
import type { PageResult, UserRole } from "@/lib/types";

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
 * In practice `role` is always "INSPECTOR" — the eligible clause is `{"role": {"in":
 * sorted(INSPECTION_ROLES)}}` and that set has one member — but it is typed and rendered as a role
 * rather than assumed, because the day the owner adds a second member to that frozenset this picker
 * must show which of the two an account is without anybody remembering to come back here.
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

/** Everyone assigned to inspect this workshop. Admin and master admin only, server-side. */
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
 * The accounts that may be assigned an inspection at all. Admin and master admin only, server-side.
 *
 * ONE ROLE AND ONE ROSTER, which is the whole difference from `eligible-viewers`: that endpoint reads
 * two rosters because it offers DESIGNERs, whose empanelment gates their sign-in, and an inspector is
 * empanelled to run nothing. The platform allow-list still applies — an account it has rejected or
 * suspended cannot sign in, so offering it here would mean an admin assigning an inspection that the
 * next sign-in refuses with nothing on screen saying why.
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
   * The server's own word for "this is a read". See {@link inspectionIsReadOnly}, which is the only
   * thing that should ever read this key directly.
   */
  readOnly?: boolean;
};

export type DwInspectableListParams = {
  page?: number;
  pageSize?: number;
  search?: string | null;
};

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
 * There is no `statusFilter`, no `mineOnly` and no `deletedOnly` here, and none of them is missing:
 * a soft-deleted workshop is a 404 for everyone but an admin, "mine" is the only scope there is, and
 * the status filter is a designer's triage of their own queue.
 */
export function listInspectableDesignWorkshops(params: DwInspectableListParams) {
  return apiFetch<PageResult<DwSummary>>(
    `/design-workshop-inspections${buildQuery({
      page: params.page,
      pageSize: params.pageSize,
      search: params.search ?? undefined
    })}`
  );
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
  if (searched && offered === 0) return "No Inspector / Reviewer account matches that search.";
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
 * is not "here is the photograph": it is "this field holds N files and this read does not carry
 * them", which the screen must say in its own words beside the field's label.
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
 *  2. **It cannot resolve the media anyway.** `GET /media/{id}` is entitled per file and an
 *     inspector holds no upload, no grant and no viewer row, so every image tile would render its
 *     "could not be read" state — which is indistinguishable from a photograph that failed to load,
 *     and is not what happened. See `kind: "media"`, which says the true thing instead.
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
