"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";

import { CappedListNotice } from "@/components/data/CappedListNotice";
import { LIST_PAGE_CEILING, listCut, mergeById, type ListCut } from "@/components/data/cappedList";
import { Field } from "@/components/FormControls";
import { LateSubmissionDialog } from "@/components/LateSubmissionDialog";
import { useRecordOffPage } from "@/components/forms/recordPickers";
import { ComboBox } from "@/components/ui/Dropdown";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import type { Workshop, WorkshopSubmissionCheck } from "@/lib/types";

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
 */

/** How far down the list the auto-default walks looking for a workshop the user may submit to. */
const DEFAULT_PROBE_LIMIT = 5;

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

const NO_WORKSHOP_LABEL = "Not linked to a workshop";

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

/**
 * Clean human label: title plus the day it ran. Never an id.
 *
 * "·" (middle dot) is the separator Android uses in `workshopOptionLabel` and in every other record
 * label, so the same workshop reads identically on both clients.
 */
function workshopOptionLabel(workshop: Workshop): string {
  const title = workshop.title?.trim() || "Untitled workshop";
  const when = formatDate(workshopOccurrenceDate(workshop) || null);
  return when === "-" ? title : `${title} · ${when}`;
}

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
  workshops: Workshop[];
  loading: boolean;
  /**
   * What the picker is NOT showing, or null when it holds every workshop — see
   * `components/data/cappedList`.
   *
   * `/workshops` clamps `pageSize` to 100 and this database holds 196 workshop rows (counted
   * 2026-08-15), so the list below is ONE PAGE, and the page is ordered by the date the workshop
   * HAPPENED — `startDate desc, date desc, createdAt desc` (`routes/workshops`, where the ordering
   * carries a long note about why it is not `createdAt`). This comment said `createdAt desc` and was
   * wrong about which row falls off the end: the cut drops the oldest-OCCURRING accessible workshop,
   * not the oldest-entered one. The client re-sort below is by the same occurrence key and therefore
   * agrees with the server rather than reordering it; **it still cannot recover what the server
   * already cut**. Rendered by <WorkshopSelect>; exposed on the selection so a form that draws its
   * own workshop control cannot silently drop the sentence.
   */
  cut: ListCut | null;
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
  const [workshops, setWorkshops] = useState<Workshop[]>([]);
  const [cut, setCut] = useState<ListCut | null>(null);
  const [loading, setLoading] = useState(true);
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
    /*
      `accessibleOnly` — see the header. Sent as the literal string "true" and omitted when off,
      because `buildQuery` takes no booleans (it stringifies, and it drops "" as if it were null).
      There is no "off" here: this control exists to be saved from.
    */
    listResource<Workshop>("/workshops", { pageSize: LIST_PAGE_CEILING, accessibleOnly: "true" })
      .then((result) => {
        if (cancelled) return;
        setWorkshops(sortWorkshopsByOccurrence(result.items));
        setCut(listCut(result, "workshops"));
      })
      .catch(() => {
        if (!cancelled) setWorkshops([]);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

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
  // the occurrence order past workshops they are not assigned to.
  useEffect(() => {
    if (isEdit || touchedRef.current || !workshops.length) return;
    let cancelled = false;
    (async () => {
      for (const workshop of workshops.slice(0, DEFAULT_PROBE_LIMIT)) {
        const result = await fetchCheck(workshop.id);
        if (cancelled || touchedRef.current) return;
        if (!result || result.canSubmit) {
          setWorkshopIdState(workshop.id);
          return;
        }
      }
      // Every recent workshop belongs to somebody else: land on the most recent anyway so the
      // inline warning can explain why, instead of silently offering nothing.
      if (!cancelled && !touchedRef.current) setWorkshopIdState(workshops[0].id);
    })();
    return () => {
      cancelled = true;
    };
  }, [workshops, isEdit, fetchCheck]);

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
  const offPageWorkshop = useRecordOffPage<Workshop>("/workshops", workshopId, workshops);
  const allWorkshops = useMemo(
    () => (offPageWorkshop ? sortWorkshopsByOccurrence(mergeById(workshops, [offPageWorkshop])) : workshops),
    [workshops, offPageWorkshop]
  );

  return {
    workshopId,
    touched,
    workshops: allWorkshops,
    loading,
    cut,
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
  const { workshopId, workshops, loading, cut, check, setWorkshopId, dialog } = state;
  // Named so the sentence explaining WHAT THE LIST IS reaches the control itself. A scoped list that
  // explains itself only to a reader who happens to look under the field is a list that reads, to
  // everybody else, as a repository with four workshops in it.
  const scopeNoteId = `${useId()}-scope`;

  const options = useMemo(
    () => [
      { value: "", label: NO_WORKSHOP_LABEL },
      ...workshops.map((workshop) => ({ value: workshop.id, label: workshopOptionLabel(workshop) }))
    ],
    [workshops]
  );

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
          options={options}
          value={workshopId}
          onChange={(next) => {
            setWorkshopId(next);
            onDirty?.();
          }}
          placeholder={loading ? "Loading workshops…" : "Select or type to search"}
          describedBy={scopeNoteId}
        />
      </Field>
      {/* The ComboBox's own search filters the array it was handed (see ui/SearchableSelect), so on a
          cut list "type to search" reaches only the rows already loaded — which is exactly when a
          researcher concludes the workshop was never recorded. Say so instead. */}
      <CappedListNotice cuts={[cut]} />
      {/*
        WHAT THE LIST IS, SAID ON SCREEN. The scope is invisible from the outside — a designer cannot
        tell "the workshops I may use" from "every workshop there is" by looking at a dropdown — and a
        narrowing nobody announced is this repository's most repeated bug class wearing a filter:
        absence reading as non-existence. The empty case gets its own sentence because it is a
        different fact and needs a different next move; it is reachable only where every workshop has
        a curated roster and this account is on none of them, which is an admin's job to fix and not a
        thing to sit and retry.
      */}
      <p id={scopeNoteId} className="text-xs text-ink-500">
        {loading
          ? "Loading the workshops you have access to…"
          : workshops.length
            ? "Only workshops you have access to are listed. Ask an admin if one you worked at is missing."
            : "No workshops are open to this account yet. A record can be saved without one, or an admin can give you access to the workshop you worked at."}
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
