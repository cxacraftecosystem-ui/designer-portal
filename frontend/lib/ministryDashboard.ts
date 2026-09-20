/**
 * THE MINISTRY DASHBOARD'S WIRE — `/api/ministry-dashboard`, typed, and the pure rules over it.
 *
 * ── A MODULE BESIDE THE PAGE, WHICH IS THIS REPOSITORY'S OWN SPLIT ──────────────────────────────
 *
 * `app/(protected)/officers/oversight.ts` beside the officers page, `components/dashboard/
 * ministryDesk.ts` beside the desk card, `components/ui/selectFilter.ts` beside the dropdowns. The
 * reason is always the same: there is no React renderer in devDependencies, so a judgement written
 * inside JSX is only ever exercised by somebody looking at a screen. Everything in this file that
 * decides anything — which standings a group covers, what a progress cell says when there is no
 * figure, which download is offered — is a function a test can call, and
 * `e2e/ministry-dashboard-unit.spec.ts` calls them.
 *
 * ── NOTHING HERE FILTERS, SORTS OR BUCKETS. EVERY NARROWING IS A QUERY PARAMETER. ───────────────
 *
 * `/sanction-orders`' page states the rule: *"NO `.filter()` AND NO `.sort()` OVER `data.items`,
 * ANYWHERE ON THIS PAGE. Every narrowing is a query parameter. A list filtered in the browser is the
 * right SIZE and has silently dropped whatever it excluded, which on a register reads as 'that order
 * was never recorded'."* On a MINISTRY register the same defect reports a national programme as the
 * size of one page, so the type switch, the standing switch and the search box are all parameters,
 * and the bucket counts come from `GET /ministry-dashboard/summary`, which counts the whole scope.
 */

import { apiFetch, buildQuery } from "@/lib/api";
import { downloadFile } from "@/lib/fileDownload";
import type { PageResult } from "@/lib/types";

/* ────────────────────────────────────────────────────────────────────────────
 * The type switch
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * WHICH TABLE THE REGISTER IS READING. Two positions, and they are two different tables rather than
 * two filters over one.
 *
 * ⚠ **THIS IS NOT `WorkshopTypeOption.key` AND MUST NOT BE COMPARED AGAINST ONE.**
 * `lib/workshopTypes.ts` states the rule in capitals — *"READ IT, DO NOT COMPARE AGAINST IT … A
 * `key === DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY` test in a picker is the bug this sentence exists to
 * prevent"* — because an administrator may add a second design-workshop-backed programme and the
 * routing fact is `routesToDesignWorkshop`, per row. That rule is about choosing WHICH LIST a record
 * form's second dropdown reads from, and it is the same distinction this switch makes, one level up:
 * "design" here means the `DesignWorkshop` table (every programme that routes at it, whatever its
 * `workshopKind`), and "other" means the legacy `Workshop` table. Nothing here tests a key.
 */
export type RegisterKind = "design" | "other";

/**
 * The first position, and the one the screen opens on.
 *
 * DESIGN & PROTOTYPE FIRST BECAUSE IT IS THE PRODUCT. The whole app exists to run design and
 * prototype workshops; the legacy `Workshop` table is the craft-documentation visit the repository
 * was built around before them. The dashboard's own tile order makes the same choice for the same
 * reason and says so at length.
 */
export const DEFAULT_REGISTER_KIND: RegisterKind = "design";

/** The switch, as the screen draws it: value, the word on the button, and what it reads. */
export const REGISTER_KINDS: ReadonlyArray<{
  value: RegisterKind;
  label: string;
  note: string;
}> = [
  {
    value: "design",
    // "Design & prototype workshops" — the nav's and the desk card's spelling of this destination,
    // ampersand and lower-case p included. A fourth spelling invented on a new screen is a name
    // nobody's grep finds and nobody's colleague recognises; `ministryDesk.ts` carries that rule.
    label: "Design & prototype workshops",
    note: "The 22-stage records designers fill in. Progress is how far through those stages each one is."
  },
  {
    value: "other",
    label: "Other workshops",
    note: "Recorded field visits. These carry no stages, so they have a review status rather than progress."
  }
];

/* ────────────────────────────────────────────────────────────────────────────
 * The standing switch
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The ministry's three words for a design & prototype workshop's standing, plus "everything".
 *
 * **EMPTY MEANS EVERYTHING, BY ABSENCE** — the same rule the workshop-scope picker and the record
 * filters follow, and the reason `buildQuery` drops `""` exactly as it drops null. Listing every
 * standing instead would silently exclude any status added to the enum after this page was written.
 *
 * ⚠ **THE MEMBERS ARE THE SERVER'S, NOT THIS FILE'S.** The server sends `standingGroups` on every
 * list response and the screen prints THAT — so a reader always sees which standings the word they
 * pressed actually covered, and a group re-cut on the server reaches the screen without a deploy.
 * The literals below are the ORDER and the WORDS only.
 */
export const DESIGN_STANDINGS: ReadonlyArray<{ value: string; label: string }> = [
  { value: "", label: "Everything" },
  { value: "ongoing", label: "Ongoing" },
  { value: "completed", label: "Completed" },
  { value: "registered", label: "Newly registered" }
];

/**
 * The other register's standings, which are `RecordStatus` and NOT the three words above.
 *
 * A recorded workshop's `status` is a REVIEW state — how far it has got through moderation — and not
 * a lifecycle. Mapping "ongoing" onto it would be a second meaning for a word the tab beside it
 * already uses for something else, invented on this screen, for rows every other surface in the
 * product describes differently. The route's own docstring carries the argument.
 */
export const OTHER_STANDINGS: ReadonlyArray<{ value: string; label: string }> = [
  { value: "", label: "Everything" },
  { value: "DRAFT", label: "Draft" },
  { value: "PENDING", label: "Pending" },
  { value: "NEEDS_REVISION", label: "Needs revision" },
  { value: "APPROVED", label: "Approved" },
  { value: "REJECTED", label: "Rejected" }
];

export function standingsFor(kind: RegisterKind) {
  return kind === "design" ? DESIGN_STANDINGS : OTHER_STANDINGS;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Rows
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * How far through its stages one workshop is.
 *
 * ⚠ **EVERY FIGURE IS NULLABLE AND `null` IS NEVER ZERO.** `overallPercent` in
 * `lib/designWorkshops.ts` returns 0 for an empty completeness map; this payload returns `null`, and
 * the difference is the whole reason the server has its own roll-up. An empty map means the stages
 * were not read — not that nothing has been done — and a ministry register printing 0% against a
 * workshop it failed to score is the single most damaging false statement this screen can make.
 * {@link progressSentence} is the one place that turns these into words.
 */
export type RegisterProgress = {
  percent: number | null;
  stagesTotal: number;
  stagesComplete: number | null;
  requiredTotal: number | null;
  requiredFilled: number | null;
  /** False when this workshop's designer-defined fields could not be read — see the sentence rule. */
  definitionRead: boolean;
  /** Present only when the workshop was NOT scored: `"capped"` or `"unreadable"`. */
  unscoredReason?: string;
  customSchemaVersion?: string;
};

/** One design & prototype row: the header columns, the progress block, the roster size. */
export type DesignRegisterRow = {
  id: string;
  title: string;
  status: string;
  workshopCode: string | null;
  workshopKind: string | null;
  craftName: string | null;
  clusterName: string | null;
  state: string | null;
  district: string | null;
  venue: string | null;
  startDate: string | null;
  endDate: string | null;
  designerName: string | null;
  updatedAt: string | null;
  /** How many artisans are on this workshop's roster. `null` means the count could not be READ —
   *  never zero, which is a real and ordinary answer on the day a workshop is opened. The server
   *  sends the two apart for the same reason {@link RegisterProgress} refuses a zero it did not
   *  measure, and the table prints them apart. */
  beneficiaries: number | null;
  standingGroup: string | null;
  progress: RegisterProgress;
};

/** One recorded-workshop row. Deliberately a different shape: it has no stages to be far through. */
export type OtherRegisterRow = {
  id: string;
  title: string;
  workshopType: string;
  place: string | null;
  date: string | null;
  startDate: string | null;
  endDate: string | null;
  status: string;
  /** See {@link DesignRegisterRow.beneficiaries} — `null` is "not read", not zero. */
  beneficiaries: number | null;
  createdAt: string | null;
  updatedAt: string | null;
};

/** What every list response carries beside its rows. */
export type RegisterMeta = {
  /** `"estate"` — every workshop; `"posted"` — the ones this officer was named on. */
  scope: "estate" | "posted" | string;
  /** The server's own sentence for that scope. Printed verbatim; never paraphrased on the client. */
  scopeLabel: string;
  standingVocabulary: string;
  /** Which standings each group name covers, as the server groups them. */
  standingGroups: Record<string, string[]>;
  progressScoreCap?: number;
};

export type DesignRegisterPage = PageResult<DesignRegisterRow> & RegisterMeta;
export type OtherRegisterPage = PageResult<OtherRegisterRow> & RegisterMeta;

/**
 * The counts behind the "at a glance" tiles.
 *
 * ⚠ **THE TWO HALVES ARE SCOPED DIFFERENTLY AND EACH CARRIES ITS OWN SENTENCE.** The design-workshop
 * counts honour the caller's posting scope; `Workshop` has no oversight relation to be narrowed by,
 * so its count is the whole table for everyone. One sentence over both — which is what this payload
 * had until review caught it — told an Assistant Director "the workshops you were named on" above a
 * NATIONAL count of recorded workshops: a tile contradicting its own caption, on the screen whose
 * entire defence against this repository's most repeated bug class IS that caption.
 */
export type RegisterSummary = {
  designWorkshops: {
    total: number;
    ongoing: number;
    completed: number;
    registered: number;
    unclassified: number;
    scope: string;
    scopeLabel: string;
  };
  otherWorkshops: { total: number; scope: string; scopeLabel: string };
  /** The DESIGN half's, repeated — the register the page opens on, and what the heading is about. */
  scope: string;
  scopeLabel: string;
};

export type RegisterEntitlements = {
  beneficiaries: boolean;
  beneficiariesRefusal: string;
};

/* ────────────────────────────────────────────────────────────────────────────
 * Endpoints
 * ──────────────────────────────────────────────────────────────────────────── */

export type RegisterQuery = {
  page?: number;
  pageSize?: number;
  search?: string | null;
  standing?: string | null;
};

/**
 * `buildQuery` drops `""` exactly as it drops null, so `standing: ""` — the "Everything" position —
 * is unsendable and therefore means EVERYTHING by absence, which is what the server reads it as.
 * That is why the switch's first value is the empty string rather than a word like "all": one state,
 * one spelling, on both sides of the wire.
 */
function registerQuery(params: RegisterQuery) {
  return buildQuery({
    page: params.page,
    pageSize: params.pageSize,
    search: params.search ?? undefined,
    standing: params.standing ?? undefined
  });
}

export function listRegisterDesignWorkshops(params: RegisterQuery) {
  return apiFetch<DesignRegisterPage>(`/ministry-dashboard/design-workshops${registerQuery(params)}`, undefined, {
    // ⚠ A TIMER ISSUES THIS REQUEST, so a 401 must NOT hard-navigate to /login. `lib/media.ts`
    // argues it for the staged-object sweep: a navigation "BY A TIMER" loses the screen the officer
    // was working on, along with their scroll position and their filter, for something they did not
    // do. `apiFetch` still clears the token, so a genuinely dead session is still noticed by
    // `AuthProvider` — which is where a decision about the session belongs.
    redirectOn401: false
  });
}

export function listRegisterOtherWorkshops(params: RegisterQuery) {
  return apiFetch<OtherRegisterPage>(`/ministry-dashboard/workshops${registerQuery(params)}`, undefined, {
    redirectOn401: false
  });
}

/* ────────────────────────────────────────────────────────────────────────────
 * The people registers
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The three populations the ministry register learned about on 2026-09-20.
 *
 * Owner: *"the dashboard carries no information about designers, ad, rd, inspectors, make it
 * extremely more capable and powerful, there is no specific card for designers where they can do
 * their stuff"*. Until then this screen was one register of WORKSHOPS and knew nothing about people
 * beyond `DesignWorkshop.designerName` — a free-typed, unindexed string with no account behind it.
 */
export type PeopleKind = "designers" | "officers" | "inspectors";

export const PEOPLE_KINDS: ReadonlyArray<{
  id: PeopleKind;
  /** The card's heading. */
  title: string;
  /** One line under it. Never the scope sentence — that one comes from the server, per list. */
  note: string;
  /** Singular; the card pluralises with a bare "s". */
  countLabel: string;
}> = [
  {
    id: "designers",
    title: "Designers",
    note: "Who is running design & prototype workshops, how many, and how far along each one is.",
    countLabel: "designer"
  },
  {
    id: "officers",
    title: "Assistant & Regional Directors",
    note: "The directorate's postings — who oversees which workshops, and in what capacity.",
    countLabel: "officer"
  },
  {
    id: "inspectors",
    title: "Inspectors",
    note: "Who inspects, how much correction they filed, and how often a workshop went back.",
    countLabel: "inspector"
  }
];

/**
 * One person in a people register.
 *
 * ⚠ **EVERY FIGURE THAT CAN BE UNMEASURED IS `number | null`, AND `null` IS NOT ZERO.** The server
 * is explicit about this and the distinction is the whole reason these lists are trustworthy: a
 * designer with no workshop scores a MEASURED zero, while a designer whose workshops could not be
 * scored carries `null` and a `progressReason`. A client that rendered both as "0" would state, in a
 * table read by the ministry, that work which exists was never done.
 */
export type RegisterPerson = {
  id: string;
  name: string | null;
  email: string | null;
  role: string;
  /** Counted over ID relations, never over the free-typed `designerName`. */
  workshops: number;
  registered: number;
  ongoing: number;
  completed: number;
  /** A status this build has not heard of — counted in `workshops` and in no group. */
  unclassifiedStanding: number;
  workshopsScored: number;
  percent: number | null;
  stagesComplete: number | null;
  stagesTotal: number | null;
  requiredTotal: number | null;
  requiredFilled: number | null;
  /** `"noWorkshops"` | `"unreadable"` | `"capped"`, or null when `percent` is a real figure. */
  progressReason: string | null;
  /** Designers only. The creator holds no viewer row, so the two arms are reported separately. */
  workshopsCreated?: number;
  workshopsNamedOn?: number;
  /** Officers only. Keyed by `OVERSIGHT_CAPACITY`; a capacity this build has not heard of falls to
   *  `unknownCapacity` rather than being dropped. */
  byCapacity?: Record<string, number>;
  unknownCapacity?: number;
  /** Inspectors only. Null when the feedback read failed — see `feedbackRead` on the page. */
  feedbackFiled?: number | null;
  sendBacks?: number | null;
};

/**
 * What every people list carries besides its rows.
 *
 * `scopeLabel` is PER LIST and is printed verbatim. This router shipped one caption over two
 * differently-scoped counts once and told an Assistant Director "the workshops you were named on"
 * above a national figure; each list now says its own scope in the server's own words, and the page
 * composes none of its own.
 */
export type RegisterPeoplePage = PageResult<RegisterPerson> & {
  scope: string;
  scopeLabel: string;
  standingVocabulary: string;
  standingGroups: Record<string, string[]>;
  scan: { total: number; read: number; truncated: boolean } & Record<string, unknown>;
  progressScoreCap: number;
  progressRead: boolean;
  /** Null when progress WAS read. A whole blank column needs a sentence, not a boolean. */
  progressNote: string | null;
  withheldAccounts: number;
  withheldAccountsNote: string | null;
  /**
   * Whether people holding nothing in this scope are listed at all, and — in the server's words —
   * what their absence would mean if they are not. The two travel together: a caption saying
   * "somebody with nothing is absent" beside a row showing exactly that person is a caption that
   * stops being believed.
   */
  includesUnpostedAccounts: boolean;
  unpostedAccountsNote: string | null;
  /** The directory read has a ceiling of its own, and a list that stopped at it must say so. */
  unpostedAccountsTruncated?: boolean;
  /**
   * DESIGNERS ONLY — how much of the empanelled roster this register structurally cannot show.
   *
   * Reported 2026-09-20: the designer roster page showed 35 and this register showed 9. The 9 was
   * arithmetically right — 24 of those addresses had no account at all and 2 held one under another
   * role — and the screen was still wrong, because nothing accounted for the other twenty-six.
   *
   * `null` means the measurement FAILED, which is not the same as nothing being missing; the note
   * beside it says which, and is the only thing that should ever be printed.
   */
  rosterRepresentation?: {
    rosterAdmitted: number;
    rosterWithoutAccount: number;
    rosterOtherRole: number;
    rosterReadTruncated: boolean;
  } | null;
  /** The server's sentence. Null when every empanelled designer is on screen. */
  rosterRepresentationNote?: string | null;
  /** Inspectors only. */
  feedbackRead?: boolean;
  feedbackNote?: string | null;
  feedbackFiledTotal?: number | null;
  feedbackAttributed?: number | null;
  feedbackByAccountsNotListed?: number | null;
};

export function listRegisterPeople(kind: PeopleKind, params: RegisterQuery) {
  return apiFetch<RegisterPeoplePage>(`/ministry-dashboard/${kind}${registerQuery(params)}`, undefined, {
    // Same reason as the two workshop registers: a TIMER issues this, so a 401 must not
    // hard-navigate and lose the screen an officer was working on.
    redirectOn401: false
  });
}

/**
 * The sentence a person's progress column prints, and it is NEVER "0%" for something unmeasured.
 *
 * The four reasons are the server's own tokens, turned into words here rather than on the wire so
 * the two clients can word them for their own readers. `progressSentence` does the same job for a
 * workshop row and this is deliberately its twin rather than a second opinion.
 */
export function personProgressSentence(person: RegisterPerson): string {
  if (typeof person.percent === "number") {
    const scored =
      person.workshopsScored === person.workshops
        ? ""
        : ` across ${person.workshopsScored} of ${person.workshops} workshops`;
    return `${person.percent}% complete${scored}`;
  }
  switch (person.progressReason) {
    case "noWorkshops":
      return "No workshops in this scope";
    case "capped":
      return "Not scored — this page is longer than the scoring ceiling";
    case "unreadable":
      return "Not scored — the stage rows could not be read";
    default:
      return "Not scored";
  }
}

export function fetchRegisterSummary() {
  return apiFetch<RegisterSummary>("/ministry-dashboard/summary", undefined, { redirectOn401: false });
}

export function fetchRegisterEntitlements() {
  return apiFetch<RegisterEntitlements>("/ministry-dashboard/entitlements", undefined, {
    redirectOn401: false
  });
}

/* ────────────────────────────────────────────────────────────────────────────
 * Downloads
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The register as a spreadsheet — THE SAME FILTERS AS THE LIST AND NO PAGING.
 *
 * An officer exporting what they are looking at must get what they are looking at; a page of twenty
 * would be a file that silently withheld the rest. `/annual-plan`'s export makes the identical trade
 * and is this route's precedent in every respect, including that it is a MINISTRY register rather
 * than designer fieldwork.
 */
export function downloadRegister(kind: RegisterKind, params: RegisterQuery) {
  const query = registerQuery({ search: params.search, standing: params.standing });
  return kind === "design"
    ? downloadFile(`/ministry-dashboard/design-workshops.csv${query}`, "design-prototype-workshops.csv")
    : downloadFile(`/ministry-dashboard/workshops.csv${query}`, "other-workshops.csv");
}

/**
 * The beneficiary list — every artisan this account may take out, with identity numbers MASKED.
 *
 * ⚠ **IT IS NOT FILTERED BY THE SCREEN, AND THE SCREEN SAYS SO.** `/api/export/artisans.csv` is the
 * third sibling of the products and tools exports and takes no parameters: it is the whole
 * beneficiary list under `can_download_dataset`, not the artisans of the workshops currently listed.
 * Silently handing over a different population than the one on screen would be worse than handing
 * over the whole one and saying which it is.
 */
export function downloadBeneficiaries() {
  return downloadFile("/export/artisans.csv", "artisans.csv");
}

/* ────────────────────────────────────────────────────────────────────────────
 * The pure rules the screen renders
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The sentence a progress cell says, in words, for a screen reader and for a printout.
 *
 * ── TWO NUMBERS BEFORE THE PERCENTAGE, DELIBERATELY ─────────────────────────────────────────────
 *
 * `lib/submissionReadiness.ts` is explicit that a bare percentage is the figure a progress surface
 * must NOT lead with: *"'82%' is the figure this screen must NOT lead with: it is the same number
 * whether the remaining 18% is one date field or a stage nobody has opened … Counting the stages and
 * then counting the fields inside them gives the two numbers that actually decide whether the
 * afternoon is enough."* At ministry scale that misreading decides which designers get chased.
 *
 * ── AND "NOT SCORED" IS NEVER "0%" ──────────────────────────────────────────────────────────────
 *
 * The two unscored reasons say different things and suggest different next moves, so they are two
 * sentences rather than one shared "no progress". Rule 10 of the frontend contract in one line: work
 * that was not done has to be visible as not done, and so does work nobody could look at.
 */
export function progressSentence(progress: RegisterProgress | null | undefined): string {
  if (!progress) return "Progress was not read for this workshop.";
  if (progress.unscoredReason === "capped") {
    return "Not scored on this page — there are more workshops here than one read scores. Narrow the list or turn the page to score this one.";
  }
  if (progress.percent === null || progress.stagesComplete === null) {
    return "Progress could not be read for this workshop. This is not zero progress — nothing was scored.";
  }
  const outstanding = (progress.requiredTotal ?? 0) - (progress.requiredFilled ?? 0);
  const stages = `${progress.stagesComplete} of ${progress.stagesTotal} stages complete`;
  const fields =
    outstanding <= 0
      ? "no required fields outstanding"
      : `${outstanding} required ${outstanding === 1 ? "field" : "fields"} outstanding`;
  return `${stages}, ${fields} — ${progress.percent}%.`;
}

/** Whether a row has a figure to draw a bar for at all. */
export function hasProgressFigure(progress: RegisterProgress | null | undefined): boolean {
  return !!progress && progress.percent !== null;
}

/**
 * One line naming which standings a chosen group covered, from the SERVER's grouping.
 *
 * Returns null for "Everything", where there is nothing to disambiguate. Printed under the switch so
 * a reader never has to guess what a word included — the rule the server's own
 * `STANDING_GROUPS` comment states, kept on the surface that shows it.
 */
export function standingMembersSentence(
  meta: Pick<RegisterMeta, "standingGroups"> | null,
  standing: string
): string | null {
  if (!standing || !meta) return null;
  const members = meta.standingGroups?.[standing];
  if (!members || members.length === 0) return null;
  const words = members.map(humaniseStanding);
  if (words.length === 1) return `Covers ${words[0]}.`;
  return `Covers ${words.slice(0, -1).join(", ")} and ${words[words.length - 1]}.`;
}

/** `PRE_SUBMISSION` → "Pre-submission". The curated spellings are `StatusBadge`'s; this is the
 *  fallback for a token this build has not heard of, and it must not invent a new word for one it
 *  has. */
function humaniseStanding(status: string): string {
  const curated: Record<string, string> = {
    DRAFT: "Draft",
    IN_PROGRESS: "In progress",
    COMPLETE: "Complete",
    PRE_SUBMISSION: "Pre-submission",
    NEEDS_REVISION: "Needs revision",
    SUBMITTED: "Submitted",
    APPROVED: "Approved",
    REJECTED: "Rejected",
    ARCHIVED: "Archived"
  };
  if (curated[status]) return curated[status];
  const words = status.replace(/_/g, " ").toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/**
 * The sentence the screen prints when the summary carries workshops in no group at all.
 *
 * `unclassified` is normally zero. It is not zero when the server stores a status this build has
 * never heard of — a deploy one release ahead — and a register whose buckets add up to less than its
 * own total, with nothing on screen to say so, is the summary contradicting its own rows. Returns
 * null at zero so the ordinary case prints nothing.
 */
export function unclassifiedSentence(count: number): string | null {
  if (count <= 0) return null;
  return count === 1
    ? "1 workshop is in a standing this page does not recognise, so it is in the total and in none of the groups. It is still listed under “Everything”."
    : `${count} workshops are in a standing this page does not recognise, so they are in the total and in none of the groups. They are still listed under “Everything”.`;
}
