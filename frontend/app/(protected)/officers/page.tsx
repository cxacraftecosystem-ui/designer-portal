"use client";

/**
 * WORKSHOP OVERSIGHT — who a design & prototype workshop is FOR, who supervises it, who inspects
 * it, and its roster.
 *
 * Five panels over one chosen workshop: the picker (which now also OPENS one), the designer team,
 * the two officer capacities, the inspectors, and the artisan list. Everything on this page is the
 * ASSIGNER's half of the sixth scope; the officer's own half is `/officers/monitored`, which this
 * account may not even be able to open.
 *
 * ── THIS PAGE SAID "Four panels" UNTIL 0.0.12, AND EVERY ASSIGNMENT ON IT WAS ADD-ONLY ────────
 *
 * The owner's sentence was "they cannot currently unassign someone". That was TRUE of three of the
 * five relations and FALSE of one, and the four answers are different in kind — which is why the
 * change is a page rewrite rather than four buttons:
 *
 *  - **Assistant Director / Regional Director** — the DELETE already existed (`PUT` with a null id)
 *    and so did a button, buried in a small grey card BELOW the name, while the directory under it
 *    offered only "Name as …". The gap was an AFFORDANCE, not a route: the unassign now lives
 *    inside the control, as `noneLabel`, where somebody looking for it will find it.
 *  - **The designer team** — the removal existed (`remove_one_viewer`) and was reachable from NO
 *    route this account could call, and this page could not even SEE who held the workshop: the
 *    read answered `designerName` as a bare string. Both halves landed in 0.0.12 —
 *    `GET …/{id}` now carries `designers`, and `PUT …/{id}/designers` is the whole-set write.
 *  - **Inspectors** — the whole feature was `require_admin`, so a Ministry Admin could stage a
 *    workshop into PRE_SUBMISSION and then cause nobody at all to inspect it. The routes moved to
 *    `require_workshop_assigner` and the panel is mounted here, told which workshop by a prop.
 *  - **The artisan roster** — no list, no removal, nowhere. `GET /artisans?designWorkshopId=…`
 *    existed and NOTHING in the product called it, which is exactly why nobody noticed the roster
 *    could not be corrected: there was no list to correct it from.
 *
 * ── THE WORKSHOP PICKER IS NOT `DesignWorkshopSelect`, AND THAT IS A BUG FIX RATHER THAN A
 * PREFERENCE ─────────────────────────────────────────────────────────────────────────────────
 *
 * That component reads `GET /design-workshops`, which does not refuse a MINISTRY_ADMIN — it scopes
 * anybody who is not an admin to the VIEWER relation, which a Ministry Admin holds on no workshop.
 * The answer is a 200 with an empty page: a screen that says there are no workshops, in a repository
 * full of them, on the first control the primary user of this page touches. This page reads
 * `GET /design-workshop-oversight/workshops` instead, which is gated on the same predicate as the
 * page itself. **It is also why `DesignWorkshopInspectorsPanel` is handed a `workshopId` here** —
 * its own picker reads that same wrong endpoint, and two selectors on one screen disagreeing about
 * whether the repository is empty is the same defect wearing a duplicate control.
 *
 * ── THE WORKSHOP IS ALSO OPENED HERE, THROUGH A THIRD DOOR AND NOT A WIDER GATE ───────────────
 *
 * `POST /design-workshops` is `assert_can_create_design_workshops` = {ADMIN, MASTER_ADMIN}, and a
 * MINISTRY_ADMIN is not an admin anywhere in this codebase — so the one account that decides who
 * every workshop is for could not open one, and the journey stopped at "choose a workshop somebody
 * else made". `POST /design-workshop-oversight/workshops` is the third door onto the SAME
 * `open_design_workshop`, exactly as the annual plan's promote route is the second. The form here
 * is deliberately NOT the 275-line create form from /design-workshops: it composes the same
 * decisions (`designerCreateFields` for the two designer keys, `WorkshopDesignerPicker` for who,
 * `workshopKindOptions` for the kind, `DateRangePicker` for the fortnight) and leaves out the
 * offline arm, because /officers is an office desktop and a local draft this account could never
 * sync is a trap rather than a safety net.
 *
 * ── FIVE PANELS, ONE WORKSHOP, AND NOTHING IS FETCHED UNTIL ONE IS CHOSEN ─────────────────────
 *
 * Every panel below the picker is scoped to the chosen workshop, so each renders a plain sentence
 * until there is one rather than an empty control that looks broken.
 *
 * ── THE PICKERS ARE SERVER-SEARCHED, NOT CLIENT-FILTERED ─────────────────────────────────────
 *
 * Both directories are capped server-side (2000 officers, 500 designers) and both take a `search`
 * parameter. A client-side filter over a server-truncated list answers "No matches" about records
 * that exist, which is this repository's most repeated bug class wearing a search box. So the box is
 * above the list — or inside the control through `serverQuery` — wired to the server, and the cut is
 * stated when it happens. The one control that DOES filter in the browser is the artisan roster,
 * because its options are one workshop's own rows and not a window over a table.
 *
 * ── A MINISTRY ADMIN IS IN THE DIRECTORY AND FITS NEITHER SLOT ───────────────────────────────
 *
 * `capacities` on each row says which slots that account may hold, and it is EMPTY for a Ministry
 * Admin. The row is drawn DISABLED with the reason rather than omitted: a directory that answers
 * "who are the officers" and silently leaves out a third of them is a directory somebody will
 * conclude is broken, and a row that is offered and then 422s is worse than one that explains
 * itself. `SelectOption.disabled` is what carries that now the control is a dropdown.
 */

import Link from "next/link";
import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { Lock, Plus, Save, UserCheck } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { PageHeader } from "@/components/PageHeader";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { DropCard } from "@/components/sketches/upload/DropCard";
import { DateRangePicker, toIsoDate } from "@/components/forms/DateTimeField";
import { Field, TextInput } from "@/components/FormControls";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown, MultiSelectDropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { WorkshopDesignerPicker } from "@/components/designworkshop/WorkshopDesignerPicker";
import { DesignWorkshopInspectorsPanel } from "@/components/settings/DesignWorkshopInspectorsPanel";
import { ApiError } from "@/lib/api";
import {
  fetchStageRegistry,
  namedDesignerTeam,
  peekStageRegistry,
  saveBlobToDisk,
  workshopKindOptions,
  MAX_NAMED_DESIGNERS,
  type DwRegistry,
  type DwSummary
} from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { canAssignWorkshopOversight, isAdmin, roleLabel } from "@/lib/permissions";
import { ImportReport } from "./ImportReport";
import {
  ARTISAN_ACCEPT,
  CAPACITIES,
  CAPACITY_LABELS,
  OVERSIGHT_SEARCH_MAX,
  artisanUploadRefusal,
  createOversightWorkshop,
  downloadArtisanProForma,
  getWorkshopOversight,
  listArtisanImports,
  listAssignableDesigners,
  listAssignableWorkshops,
  listOfficers,
  listWorkshopArtisans,
  putWorkshopDesigners,
  putWorkshopOversight,
  unlinkWorkshopArtisan,
  uploadArtisanList,
  type ArtisanImportReport,
  type DwArtisanImport,
  type DwNamedDesigner,
  type DwOfficer,
  type DwOversightCapacity,
  type DwRosterArtisan,
  type DwStaffingFilter,
  type DwViewerRow,
  type DwWorkshopOversight
} from "./oversight";

/** 300 ms, this app's number, for the reason every other debounced search here gives. */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * The failures this page can suffer, told apart in words.
 *
 * `isUnreachable` rather than `isTransient`: a repository that ANSWERED and then refused must not be
 * reported as a connection problem, or an officer spends the afternoon restarting their router over
 * a 403.
 */
function describeFailure(error: unknown, subject: string): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return `This device cannot reach the repository, so ${subject} could not be loaded. Nothing was read at all — this is not an empty list.`;
  }
  return error.message;
}

/**
 * What a refused WRITE on this page says.
 *
 * THE SERVER'S SENTENCE, ALMOST BARE, and that is the important arm rather than the fallback. Every
 * refusal on this prefix names the account, says what is wrong with it, says where the remedy is —
 * "clear that on the access screen first", "withdraw it from inspection first", "name the designer
 * it is for instead" — and ends in "Nothing was changed." Paraphrasing any of that would replace a
 * sentence an officer can act on with one they cannot.
 */
function describeRefusal(error: unknown, subject: string): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "This device cannot reach the repository, so nothing was sent and nothing has changed. Check the connection and try again.";
  }
  return error.message || `The repository refused ${subject}.`;
}

/** A debounced value, so every search box on this page behaves identically. */
function useDebounced(value: string): string {
  const [applied, setApplied] = useState(value);
  useEffect(() => {
    const term = value.trim();
    // Clearing the box does NOT wait: an empty term is the unnarrowed list, the one request that is
    // always about to be wanted and never about to be superseded by the next letter.
    const timer = window.setTimeout(() => setApplied(term), term ? SEARCH_DEBOUNCE_MS : 0);
    return () => window.clearTimeout(timer);
  }, [value]);
  return applied;
}

/** Name a person without ever leaking an id: their name, else their email, else a neutral word. */
function personLabel(person: { name?: string | null; email?: string | null } | null | undefined): string {
  return person?.name?.trim() || person?.email?.trim() || "Unknown user";
}

export default function WorkshopOversightPage() {
  const { user, loading } = useAuth();
  const allowed = canAssignWorkshopOversight(user);

  const [workshop, setWorkshop] = useState<DwSummary | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (!loading && !allowed) {
    return (
      <div>
        <PageHeader title="Workshop oversight" icon={<UserCheck className="h-5 w-5" aria-hidden />} />
        <section className="panel px-6 py-14 text-center" aria-live="polite">
          {/*
            PURPLE, DELIBERATELY, ON THE ONE PANEL OF THIS PAGE THAT IS NOT A MINISTRY SURFACE.
            Every other accent here moved to the `ministry` ramp in 0.0.12, and this padlock did not:
            a refusal is shown to somebody who is NOT a ministry account — a designer, a professor, a
            Regional Director — and painting the ministry's own colour around the notice that they
            are not of the ministry would be a lie told in colour. `AppShell` makes the same call one
            level up and stamps `data-surface="ministry"` only when the page is actually served.
          */}
          <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">
            Ministry Admin access required
          </h1>
          <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">
            Naming the designers, the Assistant Director, the Regional Director and the inspectors on
            a design &amp; prototype workshop — and uploading that workshop&rsquo;s artisan list — is
            done by a Ministry Admin, an admin or the master admin. An Assistant Director or Regional
            Director reads the workshops they have been assigned on Workshops I monitor.
          </p>
          <p className="mt-3 text-xs text-ink-500">
            You are signed in as <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>.
          </p>
          <div className="mt-7 flex flex-wrap items-center justify-center gap-3">
            <Link href="/dashboard" className="field-button">
              Back to dashboard
            </Link>
            <Link href="/officers/monitored" className="field-button-secondary">
              Workshops I monitor
            </Link>
          </div>
        </section>
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="Workshop oversight"
        description="Open a design & prototype workshop or choose one, then say who it is for, who supervises it, who inspects it, and who is on its artisan roster."
        icon={<UserCheck className="h-5 w-5" aria-hidden />}
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}

      <WorkshopPicker chosen={workshop} onChoose={setWorkshop} onError={setError} />

      {workshop ? (
        <>
          <DesignerPanel
            // REMOUNTED ON THE WORKSHOP, and that is load-bearing rather than tidy. The picker
            // inside holds a `fetchEligible` in a REF and does not re-read when the prop changes,
            // and this panel's baseline/pending pair is a whole edit in flight; keying the subject
            // is what `WorkshopDesignerPicker`'s own note asks a consumer to do.
            key={workshop.id}
            workshop={workshop}
            onError={setError}
          />
          <OversightPanel key={`oversight-${workshop.id}`} workshop={workshop} onError={setError} />
          {/*
            THE FOURTH PANEL, MOUNTED AND NOT REBUILT. It is the same component /workshop-access/
            manage draws, told which workshop rather than choosing one — see its header for why a
            second selector here would be a second, contradictory reading of the repository.

            IT SITS AFTER THE DESIGNER PANEL ON PURPOSE. `_assert_every_id_may_inspect`'s fourth
            refusal turns away anybody already on the workshop as its creator or a co-designer, so
            "name Rekha as a designer, then name Rekha as an inspector" is a natural mis-click once
            the two controls are four inches apart. Ordering them the way the decision is actually
            taken — who runs it, then who examines it — is what makes the refusal legible when it
            comes, and the panel surfaces the server's sentence verbatim rather than pre-empting it.
          */}
          <div className="mt-4">
            <DesignWorkshopInspectorsPanel key={`inspectors-${workshop.id}`} workshopId={workshop.id} />
          </div>
          <ArtisanListPanel key={`artisans-${workshop.id}`} workshop={workshop} onError={setError} />
        </>
      ) : (
        <section className="panel mt-4 p-4 text-sm text-ink-700">
          Choose a workshop above, or start one. Everything on this page is about one workshop at a
          time — who it is for, who supervises it, who inspects it, and who is on its roster.
        </section>
      )}
    </div>
  );
}

// --------------------------------------------------------------------------------------
// 1. The workshop — choose one, or open one
// --------------------------------------------------------------------------------------

/**
 * The three staffing answers, as rows.
 *
 * DECLARED AS A CONSTANT OUTSIDE THE COMPONENT so its identity never changes.
 * `SearchableSelect` re-takes its pinned-selection snapshot from an effect keyed on `options`
 * IDENTITY, so an inline array here would re-pin on every render — which is the defect written up
 * at `SearchableSelect.tsx:145-158`, and it is the one that makes the next Enter toggle the
 * neighbouring row.
 *
 * `searchable` is deliberately NOT passed: two rows of a vocabulary compiled into this file is
 * exactly what the shared threshold answers correctly on its own.
 */
const STAFFING_ROWS: DropdownOption[] = [
  { value: "staffed", label: "With a designer named" },
  { value: "unstaffed", label: "With nobody named yet" }
];

function WorkshopPicker({
  chosen,
  onChoose,
  onError
}: {
  chosen: DwSummary | null;
  onChoose: (workshop: DwSummary | null) => void;
  onError: (message: string | null) => void;
}) {
  // WHO IS READING, because the link into the workshop tree below is not offered to everybody and
  // the rule is about this account rather than about the page's gate. See that link's comment.
  const { user } = useAuth();
  const [query, setQuery] = useState("");
  const applied = useDebounced(query);
  const [staffed, setStaffed] = useState<DwStaffingFilter | "">("");
  const [rows, setRows] = useState<DwSummary[] | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [opening, setOpening] = useState(false);
  // A generation counter rather than an abort: `apiFetch` takes no `AbortSignal`, and what matters
  // is ignoring the late answer.
  const generation = useRef(0);

  const reload = useCallback(() => {
    const current = generation.current + 1;
    generation.current = current;
    listAssignableWorkshops({
      page: 1,
      pageSize: 20,
      search: applied || undefined,
      staffed: staffed || undefined
    })
      .then((page) => {
        if (generation.current !== current) return;
        setRows(page.items);
        setTruncated(page.total > page.items.length);
        onError(null);
      })
      .catch((err) => {
        if (generation.current !== current) return;
        // The list is NOT emptied. An empty list under an error banner reads as "there are no
        // workshops", which is the one thing this control must never say by accident.
        onError(describeFailure(err, "the workshop list"));
      });
  }, [applied, staffed, onError]);

  useEffect(() => {
    reload();
  }, [reload]);

  /**
   * WHY THIS LIST IS EMPTY — four answers, because only one of them is "there is nothing here".
   *
   * Saying "no workshop matches that search" to somebody who typed nothing and only moved the
   * staffing dropdown names the wrong control: the narrowing they would then go looking for is not
   * the one that is on, and the one that is on is the one that would answer their question. The two
   * staffing sentences are also worth saying as facts rather than as absences — "every workshop has
   * a designer named on it" is a genuinely good answer to the question this page is opened to ask.
   */
  const emptySentence = applied
    ? staffed
      ? "No workshop matches that search and that staffing."
      : "No workshop matches that search."
    : staffed === "unstaffed"
      ? "Every design & prototype workshop has a designer named on it."
      : staffed === "staffed"
        ? "No design & prototype workshop has a designer named on it yet."
        : "There are no design & prototype workshops yet. Start one above.";

  return (
    <section className="panel p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-display text-base font-bold tracking-tight text-ink-900">Workshop</h2>
        {chosen ? null : (
          <button
            type="button"
            className="field-button-secondary"
            onClick={() => setOpening((open) => !open)}
            aria-expanded={opening}
          >
            <Plus className="h-4 w-4" aria-hidden />
            {opening ? "Close" : "Start a new workshop"}
          </button>
        )}
      </div>

      {chosen ? (
        // S0a's inline-swap row for this file. It is an inner `div` and NOT a `.panel`, which is
        // what keeps it out of the scoped `[data-surface="ministry"] .panel` rule's way — that
        // selector is (0,2,0) and would beat a border utility on the same element.
        <div className="mt-3 flex flex-wrap items-center gap-3 rounded-md border border-ministry-200 bg-ministry-50 px-3 py-2 dark:border-ministry-900 dark:bg-ministry-950/40">
          <span className="min-w-0 flex-1">
            <span className="block truncate font-medium text-ink-900">
              {chosen.title?.trim() || "Untitled design workshop"}
            </span>
            <span className="block truncate text-xs text-ink-500">
              {chosen.workshopCode ?? "No workshop code yet"}
              {chosen.craftName ? ` · ${chosen.craftName}` : ""}
              {chosen.clusterName ? ` · ${chosen.clusterName}` : ""}
            </span>
          </span>
          <StatusBadge status={chosen.status} />
          {/*
            THE WAY INTO THE WORKSHOP ITSELF, OFFERED ONLY WHEN IT WILL RESOLVE.

            ⚠ THE SENTENCE THAT STOOD HERE WAS WRONG AND THE LINK IT JUSTIFIED 404'd FOR THE TIER
            THIS PAGE IS BUILT FOR. It read: "the three directorate tiers joined
            `DESIGN_WORKSHOP_ROLES` on 2026-09-14, so `load_workshop_or_404` admits them by ROLE and
            the workshop tree is genuinely reachable". Role is not an arm of that loader. It admits
            on `record.createdById === user.id`, OR `is_admin(user)`, OR (`can_run_design_workshops(user)`
            AND a `DesignWorkshopViewer` row) — role gates the GRANT arm only, and it is `and`, so the
            role alone short-circuits to false. A Ministry Admin holds a viewer grant on no workshop;
            this file's own header says exactly that, four paragraphs up, as the reason the picker is
            not `DesignWorkshopSelect`. `officers/oversight.ts` states the real rule correctly —
            "Reaching a workshop still needs the creator arm, an admin, or a viewer grant" — and this
            comment carried the claim without the correction.

            So the reader was shown "Open this workshop" on a workshop they were naming designers on
            one click earlier, and got the deliberately ambiguous "no such record / not one this
            account may open" sentence for it.

            WHAT IS LEFT IS THE TWO ARMS THAT DO HOLD, and between them they cover the case that made
            the link worth having: an ADMIN reads the whole tree, and a Ministry Admin who opened the
            workshop through the inline form above IS its `createdById`, so the workshop they just
            started is reachable the moment it appears here. A link rather than a button because it
            navigates; the island nav's active-route rule then lights `/design-workshops`, which is
            correct: that is where the reader now is.

            AND THE ABSENCE IS EXPLAINED RATHER THAN LEFT BLANK, which is this page's own rule for
            the Ministry Admin rows in the officer directory — a control that is simply missing is
            indistinguishable from one that is broken. `/officers/monitored` is NOT the fallback and
            must not be offered as one: `GET /design-workshop-oversight/assigned/{id}` scopes to the
            officer's OWN assignment rows, and a Ministry Admin cannot be named in either capacity.
          */}
          {chosen.createdById === user?.id || isAdmin(user) ? (
            <Link href={`/design-workshops/${chosen.id}`} className="field-button-secondary">
              Open this workshop
            </Link>
          ) : (
            <span className="text-xs leading-5 text-ink-500">
              Naming people here does not open the workshop for you — that needs a viewer row on it,
              which this account does not hold.
            </span>
          )}
          <button type="button" className="field-button-secondary" onClick={() => onChoose(null)}>
            Choose a different workshop
          </button>
        </div>
      ) : (
        <>
          {opening ? (
            <StartWorkshopForm
              onOpened={(created) => {
                setOpening(false);
                // The new workshop becomes the chosen one, so the four panels below open on it
                // immediately. There is no second read: the create answers `workshop_summary`,
                // which is the same shape this list is built from.
                onChoose(created);
              }}
              onError={onError}
            />
          ) : null}

          <div className="mt-3 grid gap-3 sm:grid-cols-[minmax(0,1fr)_14rem]">
            <SearchInput
              onChange={(next) => setQuery(next.slice(0, OVERSIGHT_SEARCH_MAX))}
              onSubmit={() => setQuery(query.trim())}
              ariaLabel="Search design workshops"
              placeholder="Search by title, craft, cluster or workshop code"
              value={query}
            />
            {/*
              WHICH OF THE TWO HUNDRED WORKSHOPS HAS NOBODY NAMED ON IT — the question a ministry
              administrator opens this page to answer, and until 0.0.12 the only way to answer it
              was to choose each workshop in turn and read the panel below.

              `noneLabel` rather than an "All workshops" row: a control that filters says everything
              BY ABSENCE, and `lib/workshopOptions` carries the argument for why a hand-built
              `{ value: "", label: … }` row in `options` is never a second one of these.

              `advanceOnSelect={false}` because this dropdown FILTERS the screen it sits on; moving
              focus away from the control being adjusted is wrong on a list funnel.
            */}
            <FieldBlock label="Staffing">
              <Dropdown
                advanceOnSelect={false}
                ariaLabel="Show workshops by whether a designer is named"
                noneLabel="Staffed and unstaffed"
                onChange={(next) => setStaffed(next as DwStaffingFilter | "")}
                options={STAFFING_ROWS}
                value={staffed}
              />
            </FieldBlock>
          </div>

          {rows === null ? (
            // null is "still asking" and [] is "genuinely none".
            <p className="mt-3 text-sm text-ink-700">Loading…</p>
          ) : rows.length === 0 ? (
            <p className="mt-3 text-sm text-ink-700">{emptySentence}</p>
          ) : (
            <ul className="mt-3 divide-y divide-line-200 rounded-md border border-line-200">
              {rows.map((row) => (
                <li key={row.id}>
                  <button
                    type="button"
                    className="flex w-full flex-col gap-1 px-3 py-2 text-left transition hover:bg-surface-50"
                    onClick={() => onChoose(row)}
                  >
                    <span className="truncate font-medium text-ink-900">
                      {row.title?.trim() || "Untitled design workshop"}
                    </span>
                    <span className="truncate text-xs text-ink-500">
                      {row.workshopCode ?? "No workshop code yet"}
                      {row.craftName ? ` · ${row.craftName}` : ""}
                      {row.startDate ? ` · ${formatDate(row.startDate)}` : ""}
                    </span>
                    {/*
                      WHOSE WORKSHOP IT IS, ON THE ROW. `designerName` has always been on the wire —
                      `workshop_summary` carries it — and this picker drew everything except the one
                      fact the page exists to change. "No designer named" is drawn rather than
                      omitted, because an absent line reads as a row that has not finished loading.
                    */}
                    <span className="truncate text-xs text-ink-500">
                      {row.designerName?.trim() ? (
                        <>
                          Designer:{" "}
                          <span className="font-medium text-ink-700">{row.designerName}</span>
                        </>
                      ) : (
                        "No designer named"
                      )}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          {truncated ? (
            // SAID, ALWAYS. A list that quietly stops is indistinguishable from a place with no more
            // records, and this one stops at twenty.
            <p className="mt-2 text-xs text-ink-500">
              Only the twenty most recent workshops are listed. Search to reach the rest.
            </p>
          ) : null}
        </>
      )}
    </section>
  );
}

/**
 * OPEN A WORKSHOP WITHOUT LEAVING THIS PAGE — the third creation door's form.
 *
 * ── IT COMPOSES THE CREATE FORM'S DECISIONS AND COPIES NONE OF ITS JSX ────────────────────────
 *
 * The 275-line form on /design-workshops is not extracted and not duplicated. What is SHARED is
 * every decision that could be got wrong twice:
 *
 *  - `designerCreateFields` (through `createOversightWorkshop`) decides which of the two designer
 *    keys reach the wire — the ONE place in this client that reads a ticked set and a chosen lead.
 *  - `WorkshopDesignerPicker` is the picker itself, with `fetchEligible` pointed at this page's own
 *    door; the `seen` label cache, the generation counter, the lead resolver and the
 *    `MAX_NAMED_DESIGNERS` trim are its, not a second copy.
 *  - `workshopKindOptions` reads the six kinds off the served registry with a compiled-in floor.
 *  - `DateRangePicker` + `toIsoDate` are the fortnight, as ONE range control: two native date boxes
 *    format themselves by the BROWSER's locale, so 02/03/2026 is February or March depending on the
 *    laptop, and two independent boxes can also be entered backwards.
 *
 * ── AND WHAT IT DELIBERATELY LEAVES OUT ──────────────────────────────────────────────────────
 *
 * **The offline arm.** `createWorkshopOrKeepItHere` exists because a designer opens a workshop in a
 * courtyard; ministry surfaces are an office desktop by standing decision, and `createLocalDraft`
 * gates on `canRunDesignWorkshops` and would strand a draft this account cannot create through the
 * ordinary door either. A failure here is reported and nothing is kept.
 *
 * **Report template, notes and the linked `Workshop` row.** The report format is chosen by whoever
 * generates the report, `notes` is the designer's own scratch column, and the `Workshop` link is a
 * designer's cross-reference. None of the three is an officer's answer, and the server's body does
 * not accept them.
 *
 * ── THE UNSAVED-CHANGES GUARD IS DELIBERATELY NOT MOUNTED ────────────────────────────────────
 *
 * `useLeaveGuard` belongs to a page whose whole content is a form. This is a disclosure ON a page
 * with four other panels, and a second `UnsavedChangesDialog` would be a second provider consumer
 * fighting the one in the protected layout over a control the reader can close with one press.
 * Closing it is one click and loses a title; the cost of getting the provider wrong is a page
 * nobody can leave.
 */
function StartWorkshopForm({
  onOpened,
  onError
}: {
  onOpened: (workshop: DwSummary) => void;
  onError: (message: string | null) => void;
}) {
  const [creating, setCreating] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [workshopKind, setWorkshopKind] = useState("");
  const [duration, setDuration] = useState<{ from?: Date; to?: Date }>({});
  const [designerUserIds, setDesignerUserIds] = useState<string[]>([]);
  const [leadDesignerId, setLeadDesignerId] = useState("");
  // Seeded from the module cache so a registry already in memory needs no round trip; the fetch
  // below refreshes it. A failure is SILENT AND CORRECT — `workshopKindOptions` falls back to the
  // compiled-in floor, which can never offer a token the server would refuse.
  const [registry, setRegistry] = useState<DwRegistry | null>(() => peekStageRegistry());

  useEffect(() => {
    let cancelled = false;
    fetchStageRegistry()
      .then((next) => {
        if (!cancelled) setRegistry(next);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  const kindChoices = useMemo(() => workshopKindOptions(registry), [registry]);
  const kindRows = useMemo<DropdownOption[]>(
    () => kindChoices.options.map((option) => ({ value: option.value, label: option.label })),
    [kindChoices]
  );

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // React nulls `event.currentTarget` across an await, so the FormData must be built before any
    // async work — not after the first `await`, where it reads as null and every field is empty.
    const form = new FormData(event.currentTarget);
    const title = String(form.get("title") ?? "").trim();
    if (!title) return;

    const text = (key: string) => {
      const value = String(form.get(key) ?? "").trim();
      return value || undefined;
    };

    setCreating(true);
    setRefusal(null);
    onError(null);
    try {
      const created = await createOversightWorkshop({
        title,
        workshopKind: workshopKind || undefined,
        // The pair is resolved by `designerCreateFields` inside the call, so the sentence the
        // picker printed about who leads it and the body on the wire cannot disagree.
        designerUserIds,
        lead: leadDesignerId,
        craftName: text("craftName"),
        clusterName: text("clusterName"),
        state: text("state"),
        district: text("district"),
        startDate: duration.from ? toIsoDate(duration.from) : undefined,
        endDate: duration.to ? toIsoDate(duration.to) : undefined
      });
      onOpened(created);
    } catch (err) {
      // PANEL-LEVEL, immediately above the buttons. A create can be refused for a reason only this
      // banner can carry — an ineligible designer anywhere in the list refuses the WHOLE create and
      // the 422 names every account it objected to.
      setRefusal(describeRefusal(err, "that workshop"));
    } finally {
      setCreating(false);
    }
  }

  return (
    <form onSubmit={submit} className="mt-3 grid gap-4 rounded-md border border-line-200 bg-surface-50 p-4">
      <div>
        <h3 className="font-display text-sm font-bold tracking-tight text-ink-900">
          Start a design workshop
        </h3>
        <p className="mt-1 text-xs leading-5 text-ink-500">
          Only the title is needed to begin. Everything else here is also asked in stage 1 and will
          be filled in from there — this is the shortcut for what is already on the sanction order.
          The workshop opens as a DRAFT and becomes the chosen one on this page.
        </p>
      </div>

      <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
        <Field label="Workshop title" required>
          <TextInput name="title" maxLength={220} required />
        </Field>
        {/* FieldBlock, not Field: `Field` is a <label>, and a <label> wrapped around a themed
            dropdown forwards a stray click into the menu and slams it shut after one pick. */}
        <FieldBlock label="Type of workshop">
          <Dropdown
            ariaLabel="Type of workshop"
            onChange={setWorkshopKind}
            options={kindRows}
            placeholder="Not stated"
            value={workshopKind}
          />
        </FieldBlock>
        <Field label="Craft">
          <TextInput name="craftName" maxLength={160} />
        </Field>
        <Field label="Cluster">
          <TextInput name="clusterName" maxLength={160} />
        </Field>
        <Field label="State">
          <TextInput name="state" maxLength={80} />
        </Field>
        <Field label="District">
          <TextInput name="district" maxLength={80} />
        </Field>
        <div className="grid gap-1 md:col-span-2">
          <DateRangePicker from={duration.from} to={duration.to} onChange={setDuration} />
        </div>
      </div>

      {/*
        FULL WIDTH AND OUTSIDE THE GRID, because it is three stacked controls (a server-backed
        search box, a notice, the picker) rather than a box. `fetchEligible` points it at THIS
        page's door: the picker's default is `GET /design-workshops/eligible-viewers`, which is
        `require_admin` and 403s the Ministry Admin this form is for.
      */}
      <WorkshopDesignerPicker
        values={designerUserIds}
        onChange={setDesignerUserIds}
        lead={leadDesignerId}
        onLeadChange={setLeadDesignerId}
        disabled={creating}
        fetchEligible={listAssignableDesigners}
      />

      {refusal ? (
        <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {refusal}
        </div>
      ) : null}

      <div className="flex flex-wrap gap-2">
        <button className="field-button" disabled={creating} type="submit">
          {creating ? "Opening…" : "Open this workshop"}
        </button>
      </div>
    </form>
  );
}

// --------------------------------------------------------------------------------------
// 2. The designers
// --------------------------------------------------------------------------------------

/**
 * WHO THE WORKSHOP IS FOR — a whole-team edit with an explicit Save.
 *
 * ── WHAT THIS REPLACED, AND WHY A LIST OF BUTTONS WAS THE WRONG SHAPE ─────────────────────────
 *
 * A flat `<ul>` of every eligible designer with a "Name as designer" button per row. Three things
 * were wrong with it and only the first was visible: one press wrote immediately, so a mis-click
 * moved a ministry report's authorship for however long the next press took; it could only ADD, so
 * a co-designer named by mistake could never be taken off from anywhere this account could reach;
 * and it never showed who currently held the workshop, because the read answered `designerName` as
 * a bare string with no ids in it.
 *
 * ── THE THREE RULES IT COPIES FROM `DesignWorkshopInspectorsPanel`, WHICH ARE THE HOUSE RULES ──
 *
 * **SAVING IS EXPLICIT.** The picker edits a PENDING set, the panel says what is unsaved, and one
 * button sends it.
 *
 * **THE OPTIONS ARE eligible ∪ CURRENTLY-ASSIGNED ∪ TICKED-THIS-SITTING.** The middle group is the
 * load-bearing one: this is a whole-set body, so an option that is not rendered is a row the next
 * Save asks the server to delete. A designer whose empanelment lapsed last week is exactly that
 * person. `WorkshopDesignerPicker` already carries the third group (its `seen` cache rescues a tick
 * made under an earlier search); the second is why `selected` is seeded from the SERVER's rows and
 * never from the eligible list.
 *
 * **THE SERVER'S ANSWER BECOMES THE BASELINE, NEVER OUR OWN PAYLOAD.** Another officer may have
 * changed the set between this page loading and Save being pressed, and treating the body we sent
 * as the truth would leave the screen showing a membership nobody has.
 *
 * ── AND ONE RULE OF ITS OWN: THE LEAD MOVES ONLY WHEN SOMEBODY MOVES IT ───────────────────────
 *
 * `leadUserId` is sent ONLY when the officer has explicitly chosen a lead in this sitting. Adding a
 * co-designer must not rewrite stage 1, must not re-copy a profile and must not restamp whose name
 * the report carries — and a panel that derived the lead from tick order would do all three on a
 * save that was about somebody else entirely. The derivation itself is `namedDesignerTeam`, the same
 * pure function the picker prints its lead line from and the create form's submit uses, so the
 * sentence on screen and the body on the wire cannot disagree.
 *
 * ⚠ **`WorkshopDesignerPicker`'S OWN COPY IS THE CREATE FORM'S AND IS PARTLY FALSE HERE.** Two of
 * its sentences — "Leave it empty if you do not know yet — stage 1 then carries whoever creates the
 * workshop" and the empty-roster notice's "this one can still be started" — assume a workshop that
 * does not exist yet, and a third points at "Designers on a workshop", a panel on
 * /workshop-access/manage that a Ministry Admin is REDIRECTED away from. The control is used rather
 * than forked, because forking is how two copies start disagreeing about the cap, the lead or the
 * label cache; the correction is the sentence this panel prints IMMEDIATELY ABOVE it, which a
 * reader meets first. Closing it properly means copy props on a file this slice does not own.
 */
function DesignerPanel({
  workshop,
  onError
}: {
  workshop: DwSummary;
  onError: (message: string | null) => void;
}) {
  /** The saved set, as the server last answered it. `null` is "still asking". */
  const [held, setHeld] = useState<DwNamedDesigner[] | null>(null);
  const [baseline, setBaseline] = useState<string[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  /**
   * The lead the officer chose IN THIS SITTING, or "" for "they have not touched it".
   *
   * KEPT APART FROM THE SERVER'S ANSWER ON PURPOSE. The picker resolves a lead for its own sentence
   * whether or not anybody chose one, and reading that resolution back as an intention would move
   * the report's authorship on a save whose only real change was adding a co-designer — silently,
   * under a 200.
   */
  const [leadChoice, setLeadChoice] = useState("");
  const [designerName, setDesignerName] = useState<string | null>(workshop.designerName ?? null);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState<string | null>(null);
  const [lostAccess, setLostAccess] = useState<DwViewerRow[]>([]);
  const [refusal, setRefusal] = useState<string | null>(null);
  const announcementId = useId();

  /** Whom the SERVER says the report names, or "" when it cannot tell. */
  const baselineLead = useMemo(
    () => held?.find((row) => row.isLead)?.userId ?? "",
    [held]
  );

  const load = useCallback(async () => {
    try {
      const detail: DwWorkshopOversight = await getWorkshopOversight(workshop.id);
      // COERCED, NOT TRUSTED: an API deployed before this key answers without it, and reading
      // `undefined` as `[]` would draw "nobody is named on this workshop" over a workshop that has
      // a whole team — the silent-emptiness bug on the one field this page exists to change.
      const rows = detail.designers;
      if (rows === undefined) {
        setHeld(null);
        setRefusal(
          "This repository cannot yet say who a workshop is for, so the team below is not shown. Nothing has been changed, and naming a different designer still works from the workshop itself."
        );
        return;
      }
      setHeld(rows);
      const ids = rows.map((row) => row.userId);
      setBaseline(ids);
      setSelected(ids);
      setLeadChoice("");
      setDesignerName(detail.designerName);
      setRefusal(null);
      onError(null);
    } catch (err) {
      onError(describeFailure(err, "this workshop's designers"));
    }
  }, [workshop.id, onError]);

  useEffect(() => {
    setSaved(null);
    setLostAccess([]);
    void load();
  }, [load]);

  /**
   * THE LEAD THIS PANEL IS ASKING FOR, AND IT IS NOTHING AT ALL WHEN NOTHING IS TICKED.
   *
   * `namedDesignerTeam` promotes a bare `lead` into a ONE-PERSON TEAM when `chosen` is empty
   * (`lib/designWorkshops.ts`), which is right for the create form — a lead named there IS the
   * choice — and is a silent re-add here. An officer who unticks the only designer on the workshop
   * would have `baselineLead` handed straight back to the resolver, and Save would post the very
   * person they had just removed, under a 200, with the screen agreeing. The baseline lead is a
   * DISPLAY resolution only, so it stands down the moment the selection is empty — which is also
   * what stops the picker printing "the report will carry …" over an empty tick list.
   */
  const leadIntent = selected.length === 0 ? "" : leadChoice || baselineLead;

  /** The team and the lead, resolved by the rule the wire uses. */
  const resolved = useMemo(
    () => namedDesignerTeam({ chosen: selected, lead: leadIntent }),
    [selected, leadIntent]
  );

  const added = useMemo(
    () => selected.filter((id) => !baseline.includes(id)),
    [selected, baseline]
  );
  const removed = useMemo(
    () => baseline.filter((id) => !selected.includes(id)),
    [baseline, selected]
  );
  // Read off `leadIntent`, not `leadChoice`, so that emptying the workshop is expressed by the
  // TEAM going empty and never as a lead move — otherwise a lead chosen earlier in the sitting and
  // then unticked would put `leadUserId` on an empty body and count as an extra unsaved change.
  const leadMoved = Boolean(leadIntent) && leadIntent !== baselineLead;
  const dirty = added.length > 0 || removed.length > 0 || leadMoved;
  const overCap = selected.length > MAX_NAMED_DESIGNERS;

  /**
   * EMPTYING A WORKSHOP IS ALLOWED, AND THIS NOTICE IS WHAT REPLACED THE REFUSAL THAT STOOD HERE.
   *
   * ⚠ **`strandsTheDesigner` LIVED ON THIS LINE AND THE REASON IT GAVE WAS FACTUALLY FALSE.** It
   * disabled Save whenever the report named somebody and nothing was ticked, and told the reader
   * that "nobody is the designer" was not a state this product could express. The product CREATES
   * that state every time a workshop is opened without naming a designer: design workshop
   * `cmsxcdc2y000` ("Test", IN_PROGRESS) sits in the live database with `designerName = None`,
   * measured there on 2026-09-20. What was missing was never the STATE — it was the TRANSITION
   * BACK to it. You could start with no designer and you could not return, so a mistaken add was a
   * one-way door, which is exactly what an officer reported: a designer added by accident, a panel
   * demanding a replacement she did not want to name, and a Save button dead beside "1 unsaved
   * change".
   *
   * The transition is symmetric now, and what replaces the refusal is NOT NOTHING. Emptying a
   * workshop is consequential, so the reader is told BEFORE the click rather than after it: the
   * notice below names all three consequences — no named designer, a cover page that then names
   * nobody, and the profile details already copied into stages 1 and 3 left exactly as they are —
   * and Save is ENABLED underneath it. Worded, not merely tinted: a signal carried only by colour
   * is one some readers never get.
   *
   * ── THE ONE REFUSAL THIS SCREEN STILL KEEPS FOR ITSELF, AND WHY IT IS SOUND ──────────────────
   *
   * Dropping the lead WHILE OTHERS REMAIN is choosing among a team, not emptying a workshop: there
   * is a replacement to name, and a report with three designers on it and no author is a state
   * nobody asked for. With nothing ticked there is no replacement and that sentence would simply be
   * wrong — hence `selected.length > 0`, this client's half of the server's `and wanted`.
   */
  const emptiesTheWorkshop = selected.length === 0 && removed.length > 0;
  const dropsTheLeadWithNoReplacement =
    Boolean(baselineLead) && removed.includes(baselineLead) && !leadMoved && selected.length > 0;

  const labelById = useMemo(() => {
    const map = new Map<string, string>();
    for (const row of held ?? []) map.set(row.userId, personLabel(row));
    return map;
  }, [held]);

  const announcement = !dirty
    ? held === null
      ? ""
      : held.length === 0
        ? "Nobody is named on this workshop. Nothing unsaved."
        : `${held.length} designer${held.length === 1 ? "" : "s"} on this workshop. Nothing unsaved.`
    : `${selected.length} designer${selected.length === 1 ? "" : "s"} selected, not yet saved. ${added.length} to add, ${removed.length} to remove${leadMoved ? ", and the report's designer changes" : ""}.`;

  async function save() {
    // `emptiesTheWorkshop` is deliberately absent from this list: it is a WARNING, not a refusal,
    // and a guard that quietly swallowed the click would be the disabled button all over again.
    if (!dirty || overCap || dropsTheLeadWithNoReplacement) return;
    setSaving(true);
    setRefusal(null);
    setSaved(null);
    try {
      const answer = await putWorkshopDesigners(workshop.id, {
        // THE WHOLE SET, and every id in it was rendered: this is a whole-set BODY, so an id the
        // screen did not draw is an id the server is being asked to remove.
        userIds: resolved.team,
        // OMITTED UNLESS SOMEBODY MOVED IT — see the state's own note.
        ...(leadMoved ? { leadUserId: resolved.lead } : {})
      });
      setHeld(answer.designers);
      const ids = answer.designers.map((row) => row.userId);
      // The ANSWER becomes the baseline, never what was sent.
      setBaseline(ids);
      setSelected(ids);
      setLeadChoice("");
      setDesignerName(answer.designerName);
      setLostAccess(answer.removedDesigners);
      // WHAT THE SERVER ACTUALLY DID. The empty answer is a real saved outcome as of 2026-09-20
      // and no longer an unreachable branch, so it says what the workshop is LEFT like rather than
      // only counting rows — and the clause under it answers the question the warning raised before
      // the click, rather than reporting a stage write that a removal does not perform.
      const nowEmpty = answer.designers.length === 0;
      setSaved(
        [
          nowEmpty
            ? "Nobody is named on this workshop. Its report will name nobody on the cover until a designer is ticked here again."
            : `${answer.designers.length} designer${answer.designers.length === 1 ? "" : "s"} can now open this workshop.`,
          nowEmpty
            ? "The profile details already copied into stages 1 and 3 stay exactly as they are — only the designer's name is gone."
            : answer.stagesWritten.length
              ? `The designer's profile was copied into ${
                  answer.stagesWritten.length === 1 ? "one stage" : `${answer.stagesWritten.length} stages`
                }.`
              : "",
          answer.removedDesigners.length
            ? `${answer.removedDesigners
                .map((row) => personLabel(row))
                .join(", ")} can no longer open it. Anything they had not yet sent is still on their own device.`
            : ""
        ]
          .filter(Boolean)
          .join(" ")
      );
    } catch (err) {
      // PANEL-LEVEL, not a toast and not the page banner: the refusal is about THIS panel's
      // control, the panel is tall, and `aria-live="polite"` never interrupts — which is exactly
      // wrong for something the reader must act on.
      setRefusal(describeRefusal(err, "that change"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="panel mt-4 p-4">
      <h2 className="font-display text-base font-bold tracking-tight text-ink-900">Designers</h2>
      <p className="mt-1 text-sm text-ink-700">
        The report names:{" "}
        <span className="font-medium text-ink-900">
          {designerName?.trim() || "nobody yet"}
        </span>
      </p>
      {/*
        THE CORRECTION, ABOVE THE CONTROL AND NOT BELOW IT. `WorkshopDesignerPicker`'s own hint is
        the create form's, and what still needs correcting here are its sentences about a workshop
        that does not exist yet ("stage 1 then carries whoever creates the workshop") and its
        pointer at "Designers on a workshop", a panel a Ministry Admin is redirected away from.
        ⚠ THIS COMMENT ALSO CORRECTED THE HINT'S "may be left empty", on the grounds that the server
        refused an empty set here. It does not, and since 2026-09-20 it must not — see
        `emptiesTheWorkshop` for the measurement that ended that rule. A reader meets this paragraph
        first. See this panel's header for why the control is composed rather than forked.
      */}
      <p className="mt-1 text-xs leading-5 text-ink-500">
        Everybody ticked below can open this workshop and fill in its stages; unticking somebody
        takes that access away when you save. One of them — named in the box under the picker — is
        the designer whose profile is copied into stages 1 and 3 and whose name the report carries.
        While other designers stay ticked that one cannot simply be dropped: name a different lead
        instead. Unticking EVERYBODY is a different act and is allowed — the workshop is then left
        with no named designer, its report names nobody on the cover, and what has already been
        copied into stages 1 and 3 stays as it is. A workshop whose report has already been handed
        in is refused outright, naming the status and the remedy.
      </p>

      {refusal ? (
        <div className="mt-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {refusal}
        </div>
      ) : null}
      {saved ? (
        <div className="mt-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">
          {saved}
        </div>
      ) : null}

      {held === null ? (
        <p className="mt-3 text-sm text-ink-700">Loading…</p>
      ) : (
        <>
          <div className="mt-3">
            <WorkshopDesignerPicker
              values={selected}
              onChange={setSelected}
              lead={leadIntent}
              onLeadChange={setLeadChoice}
              disabled={saving}
              // THIS PAGE'S DOOR, not the picker's default. `GET /design-workshops/eligible-viewers`
              // is `require_admin` and 403s a Ministry Admin; this one runs the same query behind
              // the same predicate as the page and answers the same four keys.
              fetchEligible={listAssignableDesigners}
            />
          </div>

          {/* WHAT IS CURRENTLY SAVED, spelled out — the picker's trigger says "2 selected", which
              is the PENDING answer and not the state of the repository. */}
          <div className="mt-3">
            {held.length === 0 ? (
              <p className="text-sm text-ink-500">
                Nobody holds a designer row on this workshop. Whoever opened it can still reach it —
                the creator holds a workshop through having made it, not through this list — so an
                empty list here is not the same as nobody at all.
              </p>
            ) : (
              <ul className="grid gap-1.5">
                {held.map((row) => (
                  <li
                    className="flex flex-wrap items-baseline gap-x-2 text-sm text-ink-700"
                    key={row.userId}
                  >
                    <span className="font-medium text-ink-900">{personLabel(row)}</span>
                    <span className="text-xs text-ink-500">
                      {row.email}
                      {row.role ? ` · ${roleLabel(row.role)}` : ""}
                    </span>
                    {row.isLead ? (
                      <span className="rounded-full border border-ministry-300 bg-ministry-50 px-2 py-0.5 text-[0.6875rem] font-medium text-ministry-700 dark:border-ministry-900 dark:bg-ministry-950/40 dark:text-ministry-300">
                        the report names them
                      </span>
                    ) : null}
                    {removed.includes(row.userId) ? (
                      <span className="rounded-full border border-error-600/25 bg-error-100 px-2 py-0.5 text-[0.6875rem] font-medium text-error-600">
                        loses access on save
                      </span>
                    ) : null}
                  </li>
                ))}
              </ul>
            )}
            {added.length ? (
              <p className="mt-2 text-xs leading-5 text-ink-500">
                Will be given access on save:{" "}
                {added.map((id) => labelById.get(id) ?? "a newly chosen designer").join(", ")}.
              </p>
            ) : null}
            {lostAccess.length ? (
              <p className="mt-2 text-xs leading-5 text-ink-500">
                No longer on this workshop: {lostAccess.map((row) => personLabel(row)).join(", ")}.
                Their own records, photographs and drafts are untouched — an assignment is access,
                not authorship, and nothing they wrote has been removed or re-attributed.
              </p>
            ) : null}
          </div>

          {/* ONE REFUSAL AND ONE WARNING, both stated BEFORE the Save rather than discovered from
              a 422, and both WORDED rather than merely tinted because colour alone is a signal some
              readers never get. They are mutually exclusive by construction — the refusal needs a
              non-empty selection and the warning an empty one — so the order below is for the
              reader and not for correctness: what STOPS the save comes before what merely explains
              it. Save is disabled under the refusal and ENABLED under the warning. */}
          {dropsTheLeadWithNoReplacement ? (
            <p className="mt-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
              {labelById.get(baselineLead) ?? "That designer"} is the one this report names, so
              taking them off means naming who leads it instead rather than leaving it with nobody.
              Choose the designer whose name the report should carry in the box under the picker.
            </p>
          ) : overCap ? (
            <p className="mt-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
              {selected.length} designers are selected and a workshop may be opened for at most{" "}
              {MAX_NAMED_DESIGNERS}. Untick {selected.length - MAX_NAMED_DESIGNERS} of them to save.
            </p>
          ) : emptiesTheWorkshop ? (
            <p className="mt-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
              Saving now will leave this workshop with no named designer at all. Nobody will be able
              to open it except an admin and whoever created it, and its report will name nobody on
              the cover page. The designer details already copied into stages 1 and 3 — the
              institution, the profile, the experience, the qualification and the rest — stay exactly
              as they are; only the name is cleared. You can tick a designer here again at any time.
            </p>
          ) : null}

          <div className="mt-3 flex flex-wrap items-center gap-2">
            <button
              className="field-button"
              // `emptiesTheWorkshop` is NOT in this list, and that is the whole of the fix: the
              // notice above explains the consequence and the officer decides.
              disabled={!dirty || saving || overCap || dropsTheLeadWithNoReplacement}
              onClick={save}
              type="button"
            >
              <Save className="h-4 w-4" aria-hidden />
              {saving ? "Saving…" : "Save who this workshop is for"}
            </button>
            {dirty ? (
              <button
                className="field-button-secondary"
                disabled={saving}
                onClick={() => {
                  setSelected(baseline);
                  setLeadChoice("");
                  setRefusal(null);
                }}
                type="button"
              >
                Discard changes
              </button>
            ) : null}
            {/* Colour never carries the meaning on its own — the word "unsaved" does. */}
            {dirty ? (
              <span className="text-xs text-amber-800">
                {added.length + removed.length + (leadMoved ? 1 : 0)} unsaved change
                {added.length + removed.length + (leadMoved ? 1 : 0) === 1 ? "" : "s"}
              </span>
            ) : null}
          </div>
        </>
      )}

      {/*
        THE RESULTING COUNT, PLUS WHAT IS STILL UNSENT. The multi-select has an `aria-live` of its
        own, but it lives inside a portalled panel that unmounts the moment the picker closes, so a
        reader who ticks two names and closes the panel would hear nothing about where the workshop
        now stands. Rendered from the first paint, empty or not, because assistive technology only
        announces mutations inside a region that already existed.
      */}
      <p className="sr-only" role="status" aria-live="polite" id={announcementId}>
        {announcement}
      </p>
    </section>
  );
}

// --------------------------------------------------------------------------------------
// 3. The two capacities
// --------------------------------------------------------------------------------------

/**
 * THE ASSISTANT DIRECTOR AND THE REGIONAL DIRECTOR — one searchable select each.
 *
 * ── WHY A SINGLE-SELECT AND NOT THE MULTI-SELECT THE OTHER THREE PANELS DRAW ──────────────────
 *
 * **IT IS BLOCKED AT THE DATABASE, NOT HERE.** `DesignWorkshopOversight`'s primary key is
 * `[designWorkshopId, capacity]` and the schema states the reason outright: a
 * `@@unique([designWorkshopId, userId, capacity])` would admit two Assistant Directors, and the
 * requirement — and the line on a report that prints it — has room for one. Making these
 * multi-select is a migration, a new primary key and a rewrite of `apply_oversight`,
 * `has_oversight_scope`, `oversight_by_clause` and `DesignWorkshopOversightIn` — the last of which
 * could no longer express "set / leave alone / unassign" with two scalar fields. That is a product
 * decision and a schema change, not a dropdown refactor.
 *
 * ── WHERE THE UNASSIGN WENT, AND WHY THE OWNER COULD NOT FIND IT ──────────────────────────────
 *
 * It was always here: `PUT` with a null id deletes the row, and a button said "Unassign". It sat
 * inside a small grey card BELOW the officer's name, while the directory under it offered only
 * "Name as …" — so the screen read as add-only to everybody who used it. `noneLabel` puts the same
 * act INSIDE the control that names somebody, which is where a reader looks for it: "Nobody is
 * assigned" is a row you choose, exactly like a person.
 *
 * ── EACH SAVE IS IMMEDIATE, AND THAT IS UNCHANGED ON PURPOSE ─────────────────────────────────
 *
 * The other panels batch into an explicit Save because they write a SET and an unrendered row is a
 * deletion. This one writes one scalar per request and `DesignWorkshopOversightIn` is built for it:
 * an omitted key leaves that capacity exactly as it stands, so choosing an Assistant Director while
 * a colleague names a Regional Director cannot undo their work. Batching the two would mean a body
 * that always carries both, which is the one shape that CANNOT say "leave the other one alone".
 */
function OversightPanel({
  workshop,
  onError
}: {
  workshop: DwSummary;
  onError: (message: string | null) => void;
}) {
  const [current, setCurrent] = useState<DwWorkshopOversight | null>(null);
  const [query, setQuery] = useState("");
  const applied = useDebounced(query);
  const [officers, setOfficers] = useState<DwOfficer[] | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [searching, setSearching] = useState(false);
  const [saving, setSaving] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const generation = useRef(0);
  /**
   * Every officer this mount has been shown, across every search it has run.
   *
   * MERGED, NEVER REPLACED. The option list is a moving window over the directory, so the officer
   * currently holding a post is not in the answer to the next search term — and without a
   * remembered label the trigger would fall back to naming them by their cuid. It is a label cache
   * and not a second source of eligibility: nothing is ever offered from here that the server did
   * not offer first.
   */
  const [seen, setSeen] = useState<Map<string, DwOfficer>>(() => new Map());

  const reload = useCallback(() => {
    getWorkshopOversight(workshop.id)
      .then((detail) => {
        setCurrent(detail);
        onError(null);
      })
      .catch((err) => onError(describeFailure(err, "this workshop's oversight")));
  }, [workshop.id, onError]);

  useEffect(() => {
    setCurrent(null);
    setRefusal(null);
    reload();
  }, [reload]);

  useEffect(() => {
    const gen = generation.current + 1;
    generation.current = gen;
    setSearching(true);
    listOfficers(applied || undefined)
      .then((list) => {
        if (generation.current !== gen) return;
        setOfficers(list.users);
        setTruncated(list.truncated);
        setSeen((previous) => {
          const next = new Map(previous);
          for (const officer of list.users) next.set(officer.id, officer);
          return next;
        });
        setSearching(false);
      })
      .catch((err) => {
        if (generation.current !== gen) return;
        setSearching(false);
        onError(describeFailure(err, "the officer list"));
      });
  }, [applied, onError]);

  /**
   * ONE CAPACITY PER REQUEST, and the body carries only that key.
   *
   * An omitted key leaves that capacity exactly as it stands; sending both every time would mean a
   * screen that can never say "leave the other one alone", so choosing an Assistant Director while
   * somebody else was naming a Regional Director would silently undo their work.
   */
  async function assign(capacity: DwOversightCapacity, userId: string | null) {
    setSaving(true);
    setRefusal(null);
    const body =
      capacity === "ASSISTANT_DIRECTOR"
        ? { assistantDirectorId: userId }
        : { regionalDirectorId: userId };
    try {
      const result = await putWorkshopOversight(workshop.id, body);
      // THE SERVER'S SET, NEVER AN ECHO OF WHAT WAS SENT — two administrators on one screen must not
      // each end up believing their own payload was the outcome.
      setCurrent((currently) => (currently ? { ...currently, oversight: result.oversight } : currently));
    } catch (err) {
      setRefusal(describeRefusal(err, "that officer"));
    } finally {
      setSaving(false);
    }
  }

  const held = useMemo(
    () => new Map((current?.oversight ?? []).map((row) => [row.capacity, row])),
    [current]
  );

  /**
   * The rows for one slot: who may hold it, then who may not and why, then whoever holds it now.
   *
   * **THE INELIGIBLE ARE DRAWN AND EXPLAINED RATHER THAN OMITTED**, which is the rule this panel
   * has always kept and the reason it is worth keeping: this directory answers "who are the
   * officers", and one that silently leaves out a third of them is a directory somebody will
   * conclude is broken. `SelectOption.disabled` is what carries it now the control is a dropdown,
   * and the row's `hint` says why — which `SearchableSelect` also SEARCHES, so typing "ministry"
   * finds the explanation rather than nothing.
   *
   * **AND THE HOLDER IS ALWAYS OFFERED**, even when the current search does not reach them and even
   * when their account has since been suspended off the directory. A trigger with no matching row
   * reads as though nothing were chosen, on the one control whose whole job is to say who is.
   */
  const rowsFor = useCallback(
    (capacity: DwOversightCapacity): DropdownOption[] => {
      const rows: DropdownOption[] = [];
      const offered = new Set<string>();
      const add = (officer: DwOfficer, eligible: boolean) => {
        if (offered.has(officer.id)) return;
        offered.add(officer.id);
        rows.push({
          value: officer.id,
          label: personLabel(officer),
          hint: eligible
            ? `${officer.email} · ${roleLabel(officer.role)}`
            : `${officer.email} · ${roleLabel(officer.role)} — supervises the scheme rather than one workshop, so this post is not theirs to hold`,
          disabled: !eligible,
          ...(eligible ? {} : { group: "Cannot hold this post" })
        });
      };
      for (const officer of officers ?? []) {
        if (officer.capacities.includes(capacity)) add(officer, true);
      }
      for (const officer of officers ?? []) {
        if (!officer.capacities.includes(capacity)) add(officer, false);
      }
      const holder = held.get(capacity);
      if (holder && !offered.has(holder.userId)) {
        const remembered = seen.get(holder.userId);
        add(
          remembered ?? {
            id: holder.userId,
            name: holder.name,
            email: holder.email,
            role: holder.role,
            capacities: [capacity]
          },
          true
        );
      }
      return rows;
    },
    [officers, held, seen]
  );

  return (
    <section className="panel mt-4 p-4">
      <h2 className="font-display text-base font-bold tracking-tight text-ink-900">
        Assistant Director and Regional Director
      </h2>
      <p className="mt-1 text-xs leading-5 text-ink-500">
        One of each per workshop. They can READ every stage of this workshop and change none of it —
        an oversight row is not access to the workshop, and it does not let them save anything. A
        Ministry Admin supervises the scheme rather than one workshop and cannot be named in either
        slot. Choosing &ldquo;Nobody is assigned&rdquo; takes the post off this workshop; the change
        is saved the moment it is made, and nothing else on the page moves with it.
      </p>

      {refusal ? (
        <div className="mt-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {refusal}
        </div>
      ) : null}

      <div className="mt-3 grid gap-3 sm:grid-cols-2">
        {CAPACITIES.map((capacity) => {
          const row = held.get(capacity);
          return (
            <FieldBlock key={capacity} label={CAPACITY_LABELS[capacity]}>
              {current === null ? (
                <p className="text-sm text-ink-700">Loading…</p>
              ) : (
                <>
                  <Dropdown
                    ariaLabel={`The ${CAPACITY_LABELS[capacity]} of this workshop`}
                    // FILTERS NOTHING AND FILLS A FIELD, so the default `advanceOnSelect` is right:
                    // the reader's next stop is the other slot.
                    disabled={saving}
                    // THE UNASSIGN, INSIDE THE CONTROL. See this panel's header.
                    noneLabel="Nobody is assigned"
                    onChange={(next) => assign(capacity, next || null)}
                    options={rowsFor(capacity)}
                    placeholder="Nobody is assigned"
                    /*
                      ONE TERM, BOTH SLOTS, ONE REQUEST PER KEYSTROKE. The two dropdowns read the
                      SAME directory, and only one panel can be open at a time (`AnchoredPopover`
                      keeps a stack), so a shared term costs nothing and halves the `ILIKE` scans
                      over `User` — which is the query this debounce exists for. Opening the second
                      slot with the first's term still in the box is usually right, because the two
                      officers on one workshop are usually colleagues in one office.
                    */
                    serverQuery={{
                      value: query,
                      onChange: (next) => setQuery(next.slice(0, OVERSIGHT_SEARCH_MAX)),
                      pending: searching
                    }}
                    value={row?.userId ?? ""}
                  />
                  {row ? (
                    <p className="truncate text-xs text-ink-500">
                      {row.email}
                      {row.assignedAt ? ` · supervising since ${formatDate(row.assignedAt)}` : ""}
                    </p>
                  ) : null}
                </>
              )}
            </FieldBlock>
          );
        })}
      </div>

      {officers !== null && officers.length === 0 ? (
        <p className="mt-3 text-sm text-ink-700">
          {applied
            ? "No officer matches that search."
            : "No account holds one of the three ministry posts yet. An admin sets a role on Users."}
        </p>
      ) : null}
      {truncated ? (
        <p className="mt-2 text-xs text-ink-500">
          This directory was cut. Type in either box to reach officers further down the alphabet —
          it asks the repository, so it sees every account.
        </p>
      ) : null}
    </section>
  );
}

// --------------------------------------------------------------------------------------
// 4. The artisan roster
// --------------------------------------------------------------------------------------

/**
 * WHO IS ON THIS WORKSHOP'S ARTISAN ROSTER — and how one of them is taken off again.
 *
 * ── THE LIST IS NEW, AND ITS ABSENCE IS WHY THE REMOVAL WAS NEVER MISSED ──────────────────────
 *
 * `GET /artisans?designWorkshopId=…` has existed for as long as the column has and NOTHING in this
 * product called it, on either client. This panel offered a pro-forma, an upload and an import
 * history, and never once said who was actually on the list — so "the roster cannot be corrected"
 * never surfaced as a complaint, because there was nothing to correct it from.
 *
 * ── THE CONTROL IS A MULTI-SELECT OVER THIS WORKSHOP'S OWN ROWS, AND NOT OVER THE REPOSITORY ──
 *
 * A picker over every artisan on record would be the wrong control twice over: these are fifteen to
 * forty regulated person-records that arrive BY WORKBOOK, with an Aadhaar number, a craft, a place
 * and a photograph each, and nothing about that is a thing to tick out of a dropdown of thousands.
 * So the options are the roster itself, every one of them ticked, and UNTICKING is the act. Adding
 * is the pro-forma below, which is the only door that carries the fifteen columns an artisan record
 * needs.
 *
 * `searchable` because the options are fetched RECORDS rather than a vocabulary written in this
 * file — and the browser's own filter is the right one here, unlike everywhere else on this page,
 * because these options are one workshop's whole roster and not a window over a table. Past the
 * server's ceiling the notice below says so.
 *
 * ── UNFILES, NEVER DELETES — AND SAYS WHAT IT DID NOT DO ─────────────────────────────────────
 *
 * `Artisan.designWorkshopId` is a nullable link. Clearing it leaves the artisan's record, their
 * photographs, their products, their tools and their interviews exactly as they were; `createdBy`
 * is `Restrict` and untouched. **It does NOT remove the stage-3 participant row the import wrote**,
 * because that is a stage write on a report that may be under inspection, and the sentence under
 * the control says so rather than leaving an officer to find out from a report.
 */
function ArtisanListPanel({
  workshop,
  onError
}: {
  workshop: DwSummary;
  onError: (message: string | null) => void;
}) {
  const [downloading, setDownloading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [report, setReport] = useState<ArtisanImportReport | null>(null);
  const [history, setHistory] = useState<DwArtisanImport[] | null>(null);

  const [roster, setRoster] = useState<DwRosterArtisan[] | null>(null);
  const [rosterTruncated, setRosterTruncated] = useState(false);
  const [keeping, setKeeping] = useState<string[]>([]);
  const [removing, setRemoving] = useState(false);
  const [rosterSaved, setRosterSaved] = useState<string | null>(null);

  const reloadRoster = useCallback(async () => {
    try {
      const list = await listWorkshopArtisans(workshop.id);
      setRoster(list.artisans);
      // The SERVER'S answer becomes the baseline, never our own idea of what should be left.
      setKeeping(list.artisans.map((row) => row.id));
      setRosterTruncated(Boolean(list.truncated));
    } catch (err) {
      onError(describeFailure(err, "this workshop's artisan roster"));
    }
  }, [workshop.id, onError]);

  const reloadHistory = useCallback(() => {
    listArtisanImports(workshop.id)
      .then((list) => setHistory(list.imports))
      .catch((err) => onError(describeFailure(err, "this workshop's imports")));
  }, [workshop.id, onError]);

  useEffect(() => {
    setReport(null);
    setRefusal(null);
    setHistory(null);
    setRoster(null);
    setRosterSaved(null);
    reloadHistory();
    void reloadRoster();
  }, [reloadHistory, reloadRoster]);

  const rosterOptions = useMemo<DropdownOption[]>(
    () =>
      (roster ?? []).map((row) => ({
        value: row.id,
        label: row.name,
        hint: [row.place, row.craftName, row.status].filter(Boolean).join(" · ")
      })),
    [roster]
  );
  const comingOff = useMemo(
    () => (roster ?? []).filter((row) => !keeping.includes(row.id)),
    [roster, keeping]
  );

  async function download() {
    setDownloading(true);
    setRefusal(null);
    try {
      // THE WORKSHOP ID TRAVELS, ALWAYS. With it the Details sheet names the workshop and carries
      // the State, District and Venue that blank cells fall back to — and it is what the upload
      // checks against the URL, which is what stops a list being filed under the wrong workshop.
      const file = await downloadArtisanProForma(workshop.id);
      saveBlobToDisk(file.blob, file.fileName);
    } catch (err) {
      setRefusal(describeRefusal(err, "the pro-forma"));
    } finally {
      setDownloading(false);
    }
  }

  async function upload(files: File[]) {
    const [file] = files;
    if (!file) return;
    setUploading(true);
    setRefusal(null);
    setReport(null);
    try {
      const result = await uploadArtisanList(workshop.id, file);
      setReport(result);
      reloadHistory();
      // The import writes rows into the roster, so the list above has to re-read or it would show
      // the state before the upload while the report beside it counted fourteen new artisans.
      await reloadRoster();
    } catch (err) {
      setRefusal(describeRefusal(err, "that artisan list"));
    } finally {
      setUploading(false);
    }
  }

  /**
   * Take the unticked artisans off the roster.
   *
   * SEQUENTIAL AND NOT PARALLEL, collecting failures rather than throwing at the first — the same
   * rule the bulk review actions keep. Fifteen `DELETE`s fired at once would be fifteen ways to
   * half-finish, and an officer who is shown one error has been told nothing about the other
   * fourteen. The SERVER'S list is then re-read as the new baseline, so a row another officer
   * unfiled while this one was choosing is reflected rather than resurrected.
   */
  async function removeUnticked() {
    if (!comingOff.length) return;
    setRemoving(true);
    setRefusal(null);
    setRosterSaved(null);
    const failed: string[] = [];
    let taken = 0;
    for (const row of comingOff) {
      try {
        const answer = await unlinkWorkshopArtisan(workshop.id, row.id);
        // `unlinked: false` is not a failure. Two officers working one list is ordinary, and "that
        // artisan is already off this roster" is a state to report rather than an error to raise.
        if (answer.unlinked) taken += 1;
      } catch {
        failed.push(row.name);
      }
    }
    await reloadRoster();
    setRemoving(false);
    if (failed.length) {
      setRefusal(
        `${failed.join(", ")} could not be taken off this workshop's roster, so ${
          failed.length === 1 ? "that record is" : "those records are"
        } still on it. Everything else on this page is unchanged.`
      );
      return;
    }
    setRosterSaved(
      `${taken === 1 ? "One artisan is" : `${taken} artisans are`} no longer on this workshop's roster. Their records, photographs and interviews are untouched — only the link to this workshop was cleared. Their row in stage 3's participant table is still there and is removed by a designer from the stage itself.`
    );
  }

  return (
    <section className="panel mt-4 p-4">
      <h2 className="font-display text-base font-bold tracking-tight text-ink-900">Artisan list</h2>

      {/* ── WHO IS ON IT ─────────────────────────────────────────────────────────────────────── */}
      <FieldBlock
        label="Artisans on this workshop"
        hint={
          <p className="text-xs leading-5 text-ink-500">
            Everybody ticked is filed against this workshop. Unticking somebody and saving CLEARS
            THAT LINK and nothing else — their record, their photographs, their products and their
            interviews are untouched, and nobody is deleted. Their row in stage 3&rsquo;s participant
            table stays where it is: that is a stage write, and a stage is the designer&rsquo;s to
            change. Artisans are ADDED through the pro-forma below, which is the only door that
            carries the columns an artisan record needs.
          </p>
        }
      >
        {roster === null ? (
          <p className="text-sm text-ink-700">Loading…</p>
        ) : roster.length === 0 ? (
          <p className="text-sm text-ink-700">
            No artisan is filed against this workshop yet. Download the pro-forma below, type the
            list into it and upload it.
          </p>
        ) : (
          <MultiSelectDropdown
            ariaLabel="Artisans on this workshop"
            confirmLabel="Done"
            disabled={removing}
            emptyLabel="No artisan is filed against this workshop."
            onChange={setKeeping}
            options={rosterOptions}
            placeholder="Nobody is on this roster"
            // The options are one workshop's own rows rather than a window over a table, so the
            // control's own filter box searches all of them — which is the one place on this page
            // where a client-side filter is the right search.
            searchable
            values={keeping}
          />
        )}
      </FieldBlock>

      {rosterTruncated ? (
        <p className="mt-2 text-xs leading-5 text-ink-500">
          Only the most recent 200 artisans on this workshop are listed, so this control cannot take
          anybody off beyond that. The import history below counts every row that was ever read into
          it.
        </p>
      ) : null}

      {roster && roster.length ? (
        <ul className="mt-2 grid gap-1.5">
          {roster.map((row) => (
            <li className="flex flex-wrap items-baseline gap-x-2 text-sm text-ink-700" key={row.id}>
              <span className="font-medium text-ink-900">{row.name}</span>
              <span className="text-xs text-ink-500">
                {[row.place, row.craftName].filter(Boolean).join(" · ")}
                {row.createdAt ? ` · added ${formatDate(row.createdAt)}` : ""}
              </span>
              <StatusBadge status={row.status} />
              {comingOff.some((candidate) => candidate.id === row.id) ? (
                <span className="rounded-full border border-error-600/25 bg-error-100 px-2 py-0.5 text-[0.6875rem] font-medium text-error-600">
                  comes off the roster on save
                </span>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}

      {rosterSaved ? (
        <div className="mt-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">
          {rosterSaved}
        </div>
      ) : null}

      {comingOff.length ? (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <button
            className="field-button"
            disabled={removing}
            onClick={removeUnticked}
            type="button"
          >
            <Save className="h-4 w-4" aria-hidden />
            {removing ? "Saving…" : "Save who is on this roster"}
          </button>
          <button
            className="field-button-secondary"
            disabled={removing}
            onClick={() => setKeeping((roster ?? []).map((row) => row.id))}
            type="button"
          >
            Discard changes
          </button>
          {/* Colour never carries the meaning on its own — the word does. */}
          <span className="text-xs text-amber-800">
            {comingOff.length} artisan{comingOff.length === 1 ? "" : "s"} unticked, not yet saved
          </span>
        </div>
      ) : null}

      {/* ── HOW THEY GET ON IT ───────────────────────────────────────────────────────────────── */}
      <h3 className="mt-5 font-display text-sm font-bold tracking-tight text-ink-900">
        Add artisans from the pro-forma
      </h3>
      <p className="mt-1 text-xs leading-5 text-ink-500">
        Download the pro-forma, type the list into it, and upload it. Every artisan becomes a record
        in the repository and a row in this workshop&rsquo;s stage 3 participant table. Nobody is
        created twice: anyone already recorded is linked to this workshop and their existing record
        is left exactly as it is.
      </p>
      <p className="mt-2 rounded-md border border-amber-100 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
        The pro-forma carries Aadhaar numbers, which are regulated personal data. Do not email the
        filled-in file or leave it in a shared folder, and delete it once the upload is confirmed.
        The workbook itself is never stored here — only its name, the counts, and the rows that could
        not be read.
      </p>

      <div className="mt-3 flex flex-wrap gap-2">
        <button type="button" className="field-button-secondary" disabled={downloading} onClick={download}>
          {downloading ? "Preparing…" : "Download the pro-forma"}
        </button>
      </div>

      {refusal ? (
        <div className="mt-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {refusal}
        </div>
      ) : null}

      <div className="mt-3">
        <DropCard
          label="Filled-in artisan list"
          buttonLabel="Choose the filled-in pro-forma"
          accept={ARTISAN_ACCEPT}
          acceptSentence="One Excel workbook (.xlsx), up to 4 MB."
          disabled={uploading}
          validate={artisanUploadRefusal}
          onFiles={upload}
        >
          {uploading ? <p className="text-sm text-ink-700">Reading the list…</p> : null}
        </DropCard>
      </div>

      {report ? <ImportReport report={report} /> : null}

      <h3 className="mt-4 font-display text-sm font-bold tracking-tight text-ink-900">
        Earlier uploads
      </h3>
      {history === null ? (
        <p className="mt-1 text-sm text-ink-700">Loading…</p>
      ) : history.length === 0 ? (
        <p className="mt-1 text-sm text-ink-700">
          No artisan list has been uploaded into this workshop yet.
        </p>
      ) : (
        <ul className="mt-1 divide-y divide-line-200 rounded-md border border-line-200">
          {history.map((entry) => (
            <li key={entry.id} className="px-3 py-2 text-sm">
              <span className="block truncate font-medium text-ink-900">
                {entry.sourceFilename ?? "An artisan list"}
              </span>
              <span className="block text-xs text-ink-500">
                {formatDate(entry.createdAt)}
                {entry.uploadedBy ? ` · ${entry.uploadedBy}` : ""} · {entry.rowsRead} rows read ·{" "}
                {entry.artisansCreated} created · {entry.artisansLinked} linked ·{" "}
                {entry.rowsRefused} refused
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
