"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";

import { CappedListNotice } from "@/components/data/CappedListNotice";
import { LIST_PAGE_CEILING, mergeById } from "@/components/data/cappedList";
import { Field } from "@/components/FormControls";
import { LateSubmissionDialog } from "@/components/LateSubmissionDialog";
import { useRecordOffPage } from "@/components/forms/recordPickers";
import { ComboBox, type DropdownServerQuery } from "@/components/ui/Dropdown";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import type { Workshop, WorkshopSubmissionCheck } from "@/lib/types";
import {
  NO_FIELD_WORKSHOP,
  WORKSHOP_OPTION_PAGE_SIZE,
  deviceLooksOffline,
  fieldWorkshopOptions,
  workshopCutSentence,
  workshopEmptyLabel,
  workshopListNotice,
  workshopListStandsDown,
  type WorkshopListState,
  type WorkshopListVoice,
  type WorkshopOptionSet
} from "@/lib/workshopOptions";

/**
 * The ONE workshop picker every record form mounts, above its craft / artisan / product dropdowns.
 *
 * Every record type carries a workshop now (artisan, craft, product, tool, process, questionnaire),
 * and the workshop decides two things the researcher must learn BEFORE saving, not after:
 *
 * 1. **Assignment.** Once a workshop has assignments only those researchers (and admins) may file
 *    records against it — the API answers 403. This warns at select time instead.
 * 2. **The window.** A record filed after the workshop ended is accepted but pinned to PENDING and
 *    flagged `needsAdminApproval`, so only an admin can approve it. `confirmSubmission()` surfaces
 *    that as <LateSubmissionDialog> and the save proceeds only on confirm.
 *
 * Both facts come from `GET /workshops/{id}/submission-check`, which never 403s — it only reports.
 * When that endpoint is missing or fails we degrade to a purely local "this workshop looks like it
 * ended" hint and NEVER block the save: a researcher in the field must not lose work to a flaky
 * pre-flight request.
 *
 * The selected id lives in React state and is read from `state.workshopId` at submit time — there is
 * deliberately no `name`/FormData mirror, so there is exactly one source of truth for it.
 *
 * ── WHICH LIST IS AUTHORITATIVE, AND WHY IT IS THE SERVER'S ─────────────────────────────────────
 *
 * THE OPTIONS ARE `GET /workshops?accessibleOnly=true`, AND NOTHING ELSE IS ALLOWED TO WIDEN THEM.
 * The server is the only party that can answer "may this account file against this workshop": the
 * rule is `resolve_workshop_access`, it reads `WorkshopAssignment` rows this client never sees, and
 * a workshop's curation can change between two page loads. So the request is the authority, this
 * component holds no list of its own, and there is deliberately no cached, bundled or last-known
 * fallback anywhere in this file — a stale copy of an access list is wrong in the PERMISSIVE
 * direction (a revoked grant still reads as a grant) and would put the one thing a picker must never
 * offer into the one control whose whole job is offering.
 *
 * That parameter is what turned this from a warning into a scope. It used to fetch the whole table:
 * `viewable_where` returns `{}` for every signed-in account by design (reading the repository is
 * open), so a DESIGNER was offered all 196 workshops and learned which of them were somebody else's
 * only from the red sentence below, AFTER picking one. Worse, the sentence stayed silent for the
 * common case — on an UNCURATED workshop everybody implicitly holds CONTRIBUTE, so `canSubmit` is
 * true and there is nothing to warn about. The narrowing removes exactly the rows that would 403:
 * curated rosters this account cannot write to. An admin is never narrowed.
 *
 * "CANNOT WRITE TO" AND NOT "IS NOT ON", because the first version of the server predicate tested
 * membership and never read `accessLevel` — so a designer holding a GRANTED row at VIEW on a curated
 * workshop was still offered it here and still refused by `enforce_workshop_submission` on save
 * ("your access to this workshop is view-only"), which is this sentence being false in the one case
 * it most needed to be true. The narrowing now asks for CONTRIBUTE, the level the save demands; see
 * `unreachable_workshop_ids`, whose docstring carries the whole argument, and
 * `backend/tests/test_workshop_access_scope.py`, which pins both directions.
 *
 * The pre-flight check below is kept, and is now what it always should have been: not the access
 * gate, but the answer to the two questions a scoped list still cannot answer — has this workshop
 * ENDED, and did access change since the list was fetched.
 *
 * THE ONE OPTION THAT IS NOT FROM THAT LIST is the record's own stored workshop, merged by
 * `useRecordOffPage` — see the note on `offPageWorkshop` for why removing it would be worse than
 * showing it.
 *
 * ── WHERE THE SEARCH BOX POINTS, AND WHY THAT MOVED ─────────────────────────────────────────────
 *
 * THE BOX IN THE PANEL NOW GOES TO THE SERVER (`serverQuery`), and until it did this control had
 * the defect its own cap notice was written to admit rather than to fix. `/workshops` clamps
 * `pageSize` to 100, this database holds 196 workshop rows, and the `ComboBox` this field is built
 * on FORCES a filter box on — one that filtered the array it had been handed. So a researcher who
 * typed the title of a workshop that exists but sits at row 140 was answered "No matches" about a
 * record that is in the table, and the documented consequence of that on a record form is not a
 * failed search: it is a record saved with no workshop, or filed against the wrong one, because the
 * next thing a person does after "no matches" is pick something else. `GET /workshops` has accepted
 * `search` the whole time, AND-ed with `accessibleOnly`, so the term now goes into the request and
 * the answer is about the whole accessible list rather than about page one of it.
 *
 * Two things follow, and both are deliberate. `pageSize` is `WORKSHOP_OPTION_PAGE_SIZE` — the
 * render cap, so the number fetched and the number drawn are ONE number and two truncation
 * sentences with two different totals cannot both be true. And the list this hook holds is now two
 * lists rather than one: `list` is the answer to the term in the box, which is what the options are
 * built from, while `workshops` is everything this form has learned about across every answer, which
 * is what a caller reads a title out of and what the pre-flight dates come from. Collapsing them
 * would mean a researcher typing three letters made the workshop already on the record disappear
 * out of `workshopName` on the save payload.
 *
 * ── AND THE VOCABULARY IS NOT THIS FILE'S ANY MORE ──────────────────────────────────────────────
 *
 * The label, the grouping, the sort, the "none" row, the four state sentences and the truncation
 * sentence all come from `lib/workshopOptions` and `components/ui/selectFilter`, because the four
 * record forms mount this picker directly above the DESIGN-workshop picker and every one of those
 * six facts used to be spelled differently in the two files. See `DROPDOWN_DESIGN.md` §2.
 */

/** How far down the list the auto-default walks looking for a workshop the user may submit to. */
const DEFAULT_PROBE_LIMIT = 5;

/**
 * How long a keystroke waits before it becomes a request. The same 300 ms every server-backed box
 * in this app uses (`design-review`, `DesignWorkshopViewersPanel`, `StageReferenceField`, the media
 * picker), because a researcher who has learnt how fast one of them answers is entitled to the same
 * from the rest.
 *
 * IT IS SKIPPED WHEN THE BOX IS CLEARED, and that is not a micro-optimisation: an empty box is the
 * unnarrowed list — the one request that cannot be superseded by the next letter — and making the
 * way back to the full list wait a third of a second is what teaches somebody that clearing it does
 * nothing.
 */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * The workshops this account may file against, as ONE request shared by every control on the page.
 *
 * `accessibleOnly=true` is the whole point and the header explains it; what this adds is memoisation,
 * for a caller `useWorkshopSelection` does not have: a design-workshop stage can mount the same
 * workshop-title field once per row of a collection table, and twenty participant rows issuing twenty
 * identical requests is a stage that takes a second to appear on a field connection. Same arrangement
 * and same reason as `loadAddressReference` in `components/forms/LocationFields` — including that a
 * FAILURE IS NOT CACHED, so the next control to mount asks again rather than inheriting a bad minute.
 *
 * DELIBERATELY NOT USED BY `useWorkshopSelection` BELOW. That hook fetches per mount, and it must:
 * a record form is opened, left, and opened again after an admin has granted access, and a promise
 * memoised for the life of the tab would answer the second open with the first open's refusal. The
 * stage field can afford the memo because a stage form is mounted once around a whole session of
 * typing; a record form is mounted per record.
 *
 * AND THE MEMO IS TIME-BOUNDED, WHICH IT WAS NOT. It used to hold a SUCCESS for the life of the tab,
 * so this was the one permissive cache left in the scoped path: an access change mid-session — a
 * grant revoked, a workshop curated around a designer who is not on the roster — stayed invisible to
 * `StageWorkshopField` for as long as the tab stayed open, which on this app is a whole day of
 * typing in a courtyard. That is the same "a stale copy of an access list is wrong in the PERMISSIVE
 * direction" the header refuses, differing from a cache only in where it is stored. The window is
 * now {@link ACCESSIBLE_WORKSHOPS_TTL_MS}, which still collapses the burst this memo exists for —
 * twenty participant rows mounting the same field within one render — while making the staleness
 * bounded and stateable instead of unbounded. A FAILURE IS STILL NOT CACHED AT ALL.
 */
/**
 * How long a fetched accessible-workshop list may keep answering. One minute: long enough that a
 * collection table's twenty rows share one request, short enough that "your access changed" reaches
 * the picker inside the same sitting rather than at the next reload.
 */
const ACCESSIBLE_WORKSHOPS_TTL_MS = 60_000;
let accessibleWorkshopsRequest: Promise<Workshop[]> | null = null;
let accessibleWorkshopsAskedAt = 0;

export function loadAccessibleWorkshops(): Promise<Workshop[]> {
  if (accessibleWorkshopsRequest && Date.now() - accessibleWorkshopsAskedAt > ACCESSIBLE_WORKSHOPS_TTL_MS) {
    accessibleWorkshopsRequest = null;
  }
  if (!accessibleWorkshopsRequest) {
    // Stamped when the request GOES OUT, not when it resolves: a slow answer must not extend its own
    // freshness, and the whole point of the window is how old the evidence is.
    accessibleWorkshopsAskedAt = Date.now();
    accessibleWorkshopsRequest = listResource<Workshop>("/workshops", {
      pageSize: LIST_PAGE_CEILING,
      accessibleOnly: "true"
    })
      .then((result) => sortWorkshopsByOccurrence(result.items ?? []))
      .catch((error) => {
        accessibleWorkshopsRequest = null;
        throw error;
      });
  }
  return accessibleWorkshopsRequest;
}

/*
  THE "NOT LINKED TO A WORKSHOP" ROW USED TO BE BUILT HERE. It is now `NO_FIELD_WORKSHOP`, passed to
  the picker as `noneLabel`, and the row itself is drawn by `components/ui/SearchableSelect`.

  The string is unchanged; what moved is which layer owns the ROW. Two layers entitled to draw it is
  two options sharing the React key "", a list offering one answer twice, and a control that cannot
  say which of the two is selected — and the primitive has to own it anyway, because the other
  picker on these same four forms could not draw one at all: `DesignWorkshopSelect` prepended
  nothing, so a record filed under the wrong design workshop by mistake could not be un-filed on the
  web at any point. One row, one owner, one spelling on both pickers.
*/

/**
 * The date a workshop HAPPENED — Android parity with `WorkshopDetailDto.occurrenceDate()`
 * (`startDate ?: date ?: createdAt`). Sorting on `createdAt` is wrong: a workshop entered into the
 * system last is not the workshop that ran last.
 */
export function workshopOccurrenceDate(workshop: Workshop): string {
  return workshop.startDate ?? workshop.date ?? workshop.createdAt ?? "";
}

/** Most recent by occurrence first. ISO-8601 strings compare chronologically, as on Android. */
export function sortWorkshopsByOccurrence(workshops: Workshop[]): Workshop[] {
  return [...workshops].sort((a, b) => workshopOccurrenceDate(b).localeCompare(workshopOccurrenceDate(a)));
}

/*
  `workshopOptionLabel` USED TO LIVE HERE — `title · date`, one of the six label shapes this app
  shipped for one question. It is now `fieldWorkshopOptions`, and the shape changed with the move:
  the title alone is the `label` and everything that tells two workshops apart (the place, the day,
  and "Ended" where the window has closed) is the `hint`, which `selectFilter` searches as well as
  draws. The argument is in `lib/workshopOptions` — the short version is that a date folded into the
  label gives every row a shared suffix, demotes nothing, and is what makes typing a workshop's own
  title lose to a coincidental match somewhere else in the list.
*/

/**
 * Local fallback for "has this workshop ended?", used only when the pre-flight endpoint is
 * unavailable. Mirrors the backend rule: the whole of the end day is still in-window.
 */
function endedLocally(workshop: Workshop | undefined): boolean {
  if (!workshop) return false;
  const raw = workshop.endDate ?? workshop.date ?? workshop.startDate;
  if (!raw) return false;
  const end = new Date(raw);
  if (Number.isNaN(end.getTime())) return false;
  return Date.now() >= end.getTime() + 24 * 60 * 60 * 1000;
}

export type WorkshopSelection = {
  /** The chosen workshop id ("" = not linked). Put this in the payload as `workshopId || null`. */
  workshopId: string;
  /** True once the USER picked a workshop (or the form opened on a record that already had one). */
  touched: boolean;
  /**
   * EVERY WORKSHOP THIS FORM HAS LEARNED ABOUT, most recent occurrence first — not the current
   * answer.
   *
   * The distinction was free while there was one request and is load-bearing now that the panel's
   * box goes to the server. Four forms read a title out of this array to put `workshopName` on a
   * save payload and on the carry banner; if it held the answer to the term in the box, a
   * researcher who typed three letters and did not clear them would send a payload naming no
   * workshop for a record that has one. It only ever grows (`mergeById`), so nothing a page once
   * held can be searched away from a caller.
   */
  workshops: Workshop[];
  loading: boolean;
  /**
   * WHAT THE READ ANSWERED, as three states rather than as an array that cannot tell them apart.
   *
   * `{ kind: "failed" }` used to be spelled `setWorkshops([])`, which is how a timeout came to draw
   * "No workshops are open to this account yet" — a claim about a grant table made by a request that
   * never arrived, telling a researcher to go and ask an admin for access they already hold. The
   * four sentences those states earn are `workshopListNotice`'s.
   */
  list: WorkshopListState<Workshop>;
  /**
   * The options as drawn, plus an honest account of what the answer left out — `drawn`, `total`,
   * `cut`, `truncated`, `recovered`.
   *
   * This REPLACES the `cut: ListCut | null` that used to be here. One record, from the builder that
   * decided the rows, so the sentence under the control and the rows inside it cannot disagree about
   * how many there are; `workshopCutSentence` turns it into the one sentence, and a form that draws
   * its own workshop control still cannot silently drop it.
   */
  options: WorkshopOptionSet;
  /**
   * The panel's box: the live term, the keystroke handler, and whether a read is outstanding.
   *
   * Shaped as `DropdownServerQuery` so it goes straight to `serverQuery` on the control. The
   * debounce, the page size and the discarding of an out-of-order answer are this hook's, which is
   * where `apiFetch` (no `AbortSignal`) leaves them.
   */
  search: DropdownServerQuery;
  /**
   * The term the answer IN HAND is about — not the term in the box.
   *
   * They differ for the second and a half between a keystroke and an answer, and every sentence
   * that describes the list has to be written about this one. "No workshops are open to this
   * account" printed over an answer to "zzz" is the same false claim as printing it over a failed
   * read, and the reader cannot tell either from a genuinely empty scope.
   */
  searchApplied: string;
  /** Pre-flight answer for the current selection; null while loading or when unavailable. */
  check: WorkshopSubmissionCheck | null;
  setWorkshopId: (workshopId: string) => void;
  /**
   * Call FIRST in the form's submit handler (after capturing FormData, before `setSaving(true)`).
   * Resolves true when the save may go ahead, false when the user backed out of a late submission.
   */
  confirmSubmission: () => Promise<boolean>;
  /** Internal wiring for <WorkshopSelect>; not meant for call sites. */
  dialog: { open: boolean; check: WorkshopSubmissionCheck | null; confirm: () => void; cancel: () => void };
};

/**
 * Owns the workshop selection for one form: loads the list, preselects the most recent workshop the
 * user may submit to (on CREATE only — an existing link is never clobbered), keeps the pre-flight
 * check in sync, and gates submission behind the late-submission confirmation.
 *
 * `resetKey` re-seeds the field when a single mounted form switches records (the crafts page edits
 * every craft through one inline form); pass the record's id.
 */
export function useWorkshopSelection({
  initialWorkshopId,
  isEdit = false,
  resetKey = null
}: {
  initialWorkshopId?: string | null;
  isEdit?: boolean;
  resetKey?: string | null;
} = {}): WorkshopSelection {
  /** Everything any answer has ever held, plus the record's own row. See `WorkshopSelection`. */
  const [known, setKnown] = useState<Workshop[]>([]);
  /**
   * The rows of the UNNARROWED read — the only list the default probe is allowed to walk.
   *
   * Kept apart from `known` so that a researcher who opens the picker and types before the first
   * answer lands cannot have their form pre-filled with "the most recent workshop matching the
   * three letters I happened to type". The probe preselects a workshop the user never chose; the
   * one thing it must be is the answer to the question nobody asked.
   */
  const [baseline, setBaseline] = useState<Workshop[]>([]);
  const [list, setList] = useState<WorkshopListState<Workshop>>({ kind: "loading" });
  /** The panel's box. `term` is what is typed; `searchApplied` is what the answer is about. */
  const [term, setTerm] = useState("");
  const [searchApplied, setSearchApplied] = useState("");
  /**
   * A read is outstanding — INCLUDING while the debounce timer is still counting.
   *
   * If it only covered the request, the third of a second between the last keystroke and the fetch
   * would be spent drawing the PREVIOUS term's rows with no server filter applied to them (the local
   * filter is bypassed under `serverQuery`), i.e. a list that looks like an answer and is not one.
   */
  const [pending, setPending] = useState(true);
  const [workshopId, setWorkshopIdState] = useState(initialWorkshopId ?? "");
  const [touched, setTouched] = useState(Boolean(initialWorkshopId));
  const [check, setCheck] = useState<WorkshopSubmissionCheck | null>(null);
  const [dialogCheck, setDialogCheck] = useState<WorkshopSubmissionCheck | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  // Successful pre-flight answers only. Failures are not cached, so a blip retries on the next
  // selection or on submit instead of disabling the gate for the rest of the session.
  const checkCache = useRef(new Map<string, WorkshopSubmissionCheck>());
  // Read by the async default probe, which must not fight a user who picked while it was running.
  const touchedRef = useRef(Boolean(initialWorkshopId));
  const resolveRef = useRef<((confirmed: boolean) => void) | null>(null);

  useEffect(() => {
    let cancelled = false;
    const trimmed = term.trim();
    // Announced BEFORE the timer, not inside it — see `pending`.
    setPending(true);
    const timer = window.setTimeout(() => {
      /*
        `accessibleOnly` — see the header. Sent as the literal string "true" and omitted when off,
        because `buildQuery` takes no booleans (it stringifies, and it drops "" as if it were null).
        There is no "off" here: this control exists to be saved from.

        `search` is the panel's box, AND-ed with that narrowing on the server rather than OR-ed —
        `list_workshops` appends the scope to the same `AND` list for exactly this reason, so typing
        cannot reopen a workshop this account may not file against.

        `pageSize` is the render cap and not `LIST_PAGE_CEILING`: one number for the fetch and the
        render is the only arrangement in which the sentence under the control and the rows inside
        it cannot report two different cuts.
      */
      listResource<Workshop>("/workshops", {
        pageSize: WORKSHOP_OPTION_PAGE_SIZE,
        accessibleOnly: "true",
        search: trimmed || undefined
      })
        .then((result) => {
          if (cancelled) return;
          const rows = sortWorkshopsByOccurrence(result.items);
          setList({ kind: "ok", rows, total: result.total });
          setSearchApplied(trimmed);
          // Only ever ADDS. A caller reading a title off `workshops` must not lose it because
          // somebody narrowed the panel; see `WorkshopSelection.workshops`.
          setKnown((previous) => sortWorkshopsByOccurrence(mergeById(previous, rows)));
          if (!trimmed) setBaseline(rows);
        })
        .catch(() => {
          // NOT `setKnown([])`. A failed read is a different fact from an empty list and gets a
          // different sentence (§3.5); collapsing them is what made a timeout print a claim about
          // this account's grants. What is already on screen also stays there — a workshop that was
          // listed a minute ago has not stopped existing because the next keystroke timed out.
          if (!cancelled) setList({ kind: "failed" });
        })
        .finally(() => {
          if (!cancelled) setPending(false);
        });
    }, trimmed ? SEARCH_DEBOUNCE_MS : 0);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
    /*
      ONE TIMER, AND IT IS THIS EFFECT'S, so a late answer to a term the reader has typed past is
      discarded by `cancelled` rather than by a generation counter — `apiFetch` carries no
      `AbortSignal`, and this is the arrangement `design-review` already uses for the same reason.
    */
  }, [term]);

  // Declared BEFORE the default probe so a record switch clears `touched` in the same commit that
  // the probe reads it.
  useEffect(() => {
    touchedRef.current = Boolean(initialWorkshopId);
    setTouched(Boolean(initialWorkshopId));
    setWorkshopIdState(initialWorkshopId ?? "");
  }, [resetKey, initialWorkshopId]);

  /** Pre-flight for one workshop. Never throws and never blocks: unavailable answers are null. */
  const fetchCheck = useCallback(async (id: string): Promise<WorkshopSubmissionCheck | null> => {
    if (!id) return null;
    const cached = checkCache.current.get(id);
    if (cached) return cached;
    try {
      const result = await apiFetch<WorkshopSubmissionCheck>(`/workshops/${id}/submission-check`);
      checkCache.current.set(id, result);
      return result;
    } catch {
      // Endpoint not deployed yet, offline, or a transient 5xx — the researcher still gets to save.
      return null;
    }
  }, []);

  // Create only: preselect the most recent workshop the user may actually submit to, walking down
  // the occurrence order past workshops they are not assigned to. Over `baseline` and never over
  // the current answer — see `baseline`.
  useEffect(() => {
    if (isEdit || touchedRef.current || !baseline.length) return;
    let cancelled = false;
    (async () => {
      for (const workshop of baseline.slice(0, DEFAULT_PROBE_LIMIT)) {
        const result = await fetchCheck(workshop.id);
        if (cancelled || touchedRef.current) return;
        if (!result || result.canSubmit) {
          setWorkshopIdState(workshop.id);
          return;
        }
      }
      // Every recent workshop belongs to somebody else: land on the most recent anyway so the
      // inline warning can explain why, instead of silently offering nothing.
      if (!cancelled && !touchedRef.current) setWorkshopIdState(baseline[0].id);
    })();
    return () => {
      cancelled = true;
    };
  }, [baseline, isEdit, fetchCheck]);

  // Keep the pre-flight answer in step with the selection (cache hits cost no request).
  useEffect(() => {
    let cancelled = false;
    setCheck(null);
    if (!workshopId) return;
    fetchCheck(workshopId).then((result) => {
      if (!cancelled) setCheck(result);
    });
    return () => {
      cancelled = true;
    };
  }, [workshopId, fetchCheck]);

  const setWorkshopId = useCallback((next: string) => {
    touchedRef.current = true;
    setTouched(true);
    setWorkshopIdState(next);
  }, []);

  const settleDialog = useCallback((confirmed: boolean) => {
    setDialogOpen(false);
    const resolve = resolveRef.current;
    resolveRef.current = null;
    resolve?.(confirmed);
  }, []);

  const confirmSubmission = useCallback(async (): Promise<boolean> => {
    if (!workshopId) return true;
    const result = await fetchCheck(workshopId);
    // No answer (endpoint missing/errored) or the workshop is still running: save as normal.
    if (!result || (!result.outOfWindow && !result.isOver)) return true;
    setDialogCheck(result);
    setDialogOpen(true);
    return new Promise<boolean>((resolve) => {
      resolveRef.current = resolve;
    });
  }, [workshopId, fetchCheck]);

  // A form unmounted mid-dialog must not leave its submit promise hanging forever.
  useEffect(() => {
    return () => resolveRef.current?.(false);
  }, []);

  /**
   * THE RECORD'S OWN WORKSHOP IS ALWAYS AN OPTION, however old it is.
   *
   * The list is one page of 196 rows ordered by creation, so editing a record filed against a
   * workshop from an earlier season drew a picker with the stored id as its value and no option
   * matching it — the control read as blank, and the obvious repair for a blank workshop is to pick
   * a different one, which re-files the record against the wrong fortnight. Merged and RE-SORTED, so
   * the recovered row sits in occurrence order rather than at the end. See `useRecordOffPage`.
   *
   * Create forms never reach this: `workshopId` is "" until the default probe or the user picks.
   *
   * AND IT IS THE ONE OPTION THE ACCESS SCOPE DOES NOT GOVERN, which is a deliberate exception and
   * not a hole. `GET /workshops/{id}` is open to every signed-in account, so this can recover a
   * workshop `accessibleOnly=true` has just excluded — a record filed months ago against a roster
   * this designer has since been taken off. Withholding it does not withhold anything: the record is
   * ALREADY filed there, the id is already in `workshopId`, and all that changes is that the control
   * renders blank. The documented consequence of a blank workshop box is that somebody repairs it by
   * picking a different one, which re-files the record against the wrong fortnight — so hiding the
   * row would convert a read-only fact into a wrong write. It is also not an OFFER: it is the answer
   * already on the record, and the red sentence below says out loud that saving against it will be
   * refused. Nothing here can reach a workshop the account was never given.
   */
  const offPageWorkshop = useRecordOffPage<Workshop>("/workshops", workshopId, known);
  const allWorkshops = useMemo(
    () => (offPageWorkshop ? sortWorkshopsByOccurrence(mergeById(known, [offPageWorkshop])) : known),
    [known, offPageWorkshop]
  );

  /**
   * The row to hand `offPage`, taken from EVERYTHING THIS FORM KNOWS and not only from the by-id
   * read.
   *
   * The by-id read alone was enough while the options were one fixed page. It stopped being enough
   * the moment the box went to the server: a record whose workshop IS on page one is never fetched
   * by id (`useRecordOffPage` skips it, correctly), so the instant a term narrowed that row out of
   * the answer there was nothing to merge back, the control lost its own value, and the trigger
   * fell back to the placeholder over a record that has a workshop. The panel's box does not clear
   * itself on close either — the term is the caller's — so that state survives being dismissed, and
   * a blank workshop box is the one thing this whole file exists to prevent: somebody repairs it by
   * picking a different workshop and re-files the record against the wrong fortnight.
   */
  const storedWorkshop = useMemo(
    () => allWorkshops.find((workshop) => workshop.id === workshopId) ?? null,
    [allWorkshops, workshopId]
  );

  /**
   * `offPage: "recover"`, which is this file's own ruling made explicit rather than re-derived.
   *
   * The builder merges the stored row ONLY when the answer does not already contain it, and draws it
   * under "Already on this record" — its own heading, so the scope sentence under the control stays
   * true of everything under "Open". `"refuse"` is the other branch and belongs to a control that
   * AUTHORISES A WRITE it cannot take back (`AdoptLocalDraftDialog`); this one describes a read that
   * is already true.
   *
   * `group: true` because the difference between a workshop that is still running and one whose
   * window has closed is a fact the researcher must act on before saving, not after.
   */
  const options = useMemo(
    () => fieldWorkshopOptions(list, { group: true, offPage: { mode: "recover", row: storedWorkshop } }),
    [list, storedWorkshop]
  );

  const search = useMemo<DropdownServerQuery>(
    // No `truncated`: `/workshops` answers with a `total`, so the exact sentence is available and is
    // drawn once, under the field, by `workshopCutSentence`. Setting the flag as well would put the
    // vaguer of two true sentences ("more than are drawn, and the server did not say how many")
    // inside the panel over the precise one underneath it, and a control that describes one cut in
    // two wordings has taught the reader that neither is worth reading.
    () => ({ value: term, onChange: setTerm, pending }),
    [term, pending]
  );

  return {
    workshopId,
    touched,
    workshops: allWorkshops,
    loading: list.kind === "loading",
    list,
    options,
    search,
    searchApplied,
    check,
    setWorkshopId,
    confirmSubmission,
    dialog: {
      open: dialogOpen,
      check: dialogCheck,
      confirm: () => settleDialog(true),
      cancel: () => settleDialog(false)
    }
  };
}

/**
 * The workshop field itself: a searchable ComboBox (workshop lists get long), the assignment and
 * late-submission warnings for the current pick, and the confirmation dialog its `confirmSubmission`
 * opens. Mount it as the first dropdown in the form and hang `onDirty` on it — picking a workshop
 * only updates React state and never fires a form event, so the unsaved-changes guard needs telling.
 *
 * The reverse also has to be handled: the ComboBox's search box IS a real `<input type="text">`, and
 * the record forms mark themselves dirty from `<form onInput={markDirty}>`. Left alone, merely TYPING
 * to filter the list — changing nothing — would arm the "unsaved changes" prompt, so a user who
 * searched, picked nothing and pressed Escape could not leave without confirming. The wrapper below
 * therefore swallows the native input event; the only thing that reports dirtiness here is an actual
 * selection, through `onDirty` in `onChange`.
 */
export function WorkshopSelect({
  state,
  label = "Workshop",
  onDirty,
  saving
}: {
  state: WorkshopSelection;
  label?: string;
  onDirty?: () => void;
  saving?: boolean;
}) {
  const { workshopId, workshops, list, options, search, searchApplied, check, setWorkshopId, dialog } = state;
  // Named so the sentence explaining WHAT THE LIST IS reaches the control itself. A scoped list that
  // explains itself only to a reader who happens to look under the field is a list that reads, to
  // everybody else, as a repository with four workshops in it.
  const baseId = useId();
  const scopeNoteId = `${baseId}-scope`;
  // The cut gets its own id and is named alongside the scope sentence, because they are two facts
  // about the list — what it IS and what it LEFT OUT — and a screen-reader user needs both AT the
  // control rather than one of them somewhere underneath it. `aria-describedby` takes both.
  const cutNoteId = `${baseId}-cut`;

  /**
   * WHICH LIST THIS IS, for the four sentences. `scoped` is true because the request carries
   * `accessibleOnly=true`: an empty answer here means "no workshop is open to this account", whose
   * next move is an administrator — never "no workshops have been recorded", whose next move is to
   * create one. `deviceLooksOffline` splits the failure sentence in two the same way.
   */
  const voice: WorkshopListVoice = { table: "field", scoped: true, online: !deviceLooksOffline() };

  /**
   * §3.5's sentence about the list, or "" when the list has nothing to explain.
   *
   * SUPPRESSED WHILE THE ANSWER IS ABOUT A TERM, and that guard is the whole reason `searchApplied`
   * is on the selection. "No workshops are open to this account" is a claim about a SCOPE; over an
   * answer to "zzz" it is simply false, and it is false in the direction that sends a researcher to
   * an administrator about access they already have. The panel says the true thing in that state —
   * `serverNoMatchSentence`, which is a claim about the whole list because the term went to the
   * server — and it says it where the reader is looking. A FAILED read still speaks, term or no
   * term: the read failing is not something the box did.
   */
  const listNotice = list.kind === "ok" && searchApplied ? "" : workshopListNotice(list, voice);

  /**
   * R2 and R3: nothing to pick means the control is disabled AND a sentence says why. A required
   * closed list with no members refuses the submit before the offline outbox is ever reached, and
   * the interview and its photographs die with the tab (`LocationFields`, whose two required flags
   * both end in `&& options.length > 0`).
   *
   * NOT WHILE THE BOX HOLDS A TERM, which is not a softening of the rule but the only way to obey it
   * here: the box lives INSIDE the panel, so disabling the trigger makes the panel unopenable and
   * the term unclearable, and a reader who typed something that matched nothing would be locked out
   * of the control by their own keystroke with no way back. A read still in flight does not stand
   * anything down either — "there is nothing to pick" is a claim, and mid-flight it is not one this
   * knows to be true.
   */
  const standingDown = !search.pending && !search.value.trim() && workshopListStandsDown(options);

  const selected = workshops.find((workshop) => workshop.id === workshopId);
  const blocked = Boolean(check && !check.canSubmit);
  // Prefer the server's verdict; fall back to local dates when the pre-flight is unavailable.
  const late = check ? check.outOfWindow || check.isOver : Boolean(workshopId) && endedLocally(selected);
  const endLabel = formatDate(check?.endDate ?? selected?.endDate ?? selected?.date ?? null);

  return (
    // Search keystrokes stop here instead of bubbling to the form's `onInput` dirty tracker (see the
    // note above). Nothing inside this subtree is a form control the parent needs input events from.
    <div className="grid min-w-0 content-start gap-1" onInput={(event) => event.stopPropagation()}>
      <Field label={label}>
        <ComboBox
          options={options.options}
          value={workshopId}
          onChange={(next) => {
            setWorkshopId(next);
            onDirty?.();
          }}
          /*
            Nearly unreachable now, and deliberately kept for the case that is left. With `noneLabel`
            set the trigger reads back the "none" row whenever the value is "", so the placeholder is
            only ever seen while an EDIT form holds an id whose row has not arrived — which is
            exactly the second where "Loading workshops…" is the true thing to say and "Select or
            type to search" would be an invitation to overwrite a link that is already there.
          */
          placeholder={list.kind === "loading" ? "Loading workshops…" : "Select or type to search"}
          /*
            The panel's own line when the list holds nothing — never the literal "No options", and
            never a claim the state does not support. It is `SEARCHING_LABEL` mid-flight, the failure
            sentence after a failure, and the scoped "no workshops are open to this account" only
            when the read actually answered with none. With a term typed the panel draws
            `serverNoMatchSentence` instead and this is not reached.
          */
          emptyLabel={workshopEmptyLabel(list, voice)}
          /*
            THE ROW THAT UN-FILES THE RECORD, drawn by the primitive: first, ungrouped, exempt from
            the render cap, and hidden while a term is active because it is not a row of the corpus
            and a reader who is typing is hunting rather than un-filing.
          */
          noneLabel={NO_FIELD_WORKSHOP}
          serverQuery={search}
          disabled={standingDown}
          describedBy={`${scopeNoteId} ${cutNoteId}`}
        />
      </Field>
      {/*
        WHAT THE LIST LEFT OUT. The box now reaches past the cut — it goes to the server's `search`,
        AND-ed with the access scope — so the sentence names the box ("Keep typing to narrow the
        list") and is true for the first time; before this pass the same field printed a cap notice
        under a box that could only sift the rows already drawn.

        Worded by `workshopCutSentence` and rendered by `CappedListNotice`. The split is deliberate:
        the DECISION stays in a module (five screens describing one cut in five sentences teaches a
        reader that none of them means much), and the sentence chosen is the panel's vocabulary
        rather than the page's, because the box it points at is inside the panel — `cappedListNotice`'s
        "type in the box above" would be naming a control that is not above anything here.
      */}
      <CappedListNotice
        cuts={[workshopCutSentence(options, { term: searchApplied, searchable: true })]}
        id={cutNoteId}
      />
      {/*
        WHAT THE LIST IS, SAID ON SCREEN. The scope is invisible from the outside — a designer cannot
        tell "the workshops I may use" from "every workshop there is" by looking at a dropdown — and a
        narrowing nobody announced is this repository's most repeated bug class wearing a filter:
        absence reading as non-existence.

        The empty and failed cases are no longer spelled here. They are four different facts with
        four different next moves — wait, connect, ask an administrator, create one — and this
        paragraph could only ever say one of them, which is how a failed read came to print a
        sentence about this account's grants. `workshopListNotice` picks; when it has nothing to
        report (the list arrived, or is still arriving) the scope sentence stands, which is true in
        both of those states and does not flicker between three wordings on a slow connection.
      */}
      <p id={scopeNoteId} className="text-xs text-ink-500">
        {listNotice || "Only workshops you have access to are listed. Ask an admin if one you worked at is missing."}
      </p>
      {blocked ? (
        <p className="text-xs font-medium text-error-600">
          You are not assigned to this workshop, so saving will be refused. Ask an admin to assign you to it, or pick
          another workshop.
        </p>
      ) : null}
      {late && !blocked ? (
        <p className="text-xs font-medium text-amber-800">
          {endLabel === "-" ? "This workshop has already ended." : `This workshop ended on ${endLabel}.`}{" "}
          {check?.needsAdminApproval || !check
            ? "Saving now counts as a late submission and needs an admin's approval."
            : "Saving now is recorded as a late submission."}
        </p>
      ) : null}
      <LateSubmissionDialog
        open={dialog.open}
        workshopTitle={dialog.check?.title ?? selected?.title}
        endDate={dialog.check?.endDate ?? selected?.endDate ?? selected?.date}
        needsAdminApproval={Boolean(dialog.check?.needsAdminApproval)}
        saving={saving}
        onConfirm={dialog.confirm}
        onCancel={dialog.cancel}
      />
    </div>
  );
}
