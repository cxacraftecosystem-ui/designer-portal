"use client";

/**
 * The ONE design-and-prototype-workshop picker every record form mounts, beside its `WorkshopSelect`.
 *
 * ── WHY IT IS A SECOND PICKER AND NOT A SECOND KIND OF ROW IN THE FIRST ─────────────────────────
 *
 * `forms/WorkshopSelect.tsx` picks a `Workshop` — the ordinary field/training workshop, gated by
 * `WorkshopAssignment` through `resolve_workshop_access`, and subject to the submission window and
 * the late-submission dialog. This picks a `DesignWorkshop` — the 22-stage design and prototype
 * record, gated by `load_workshop_or_404` through creator/admin/`DesignWorkshopViewer`. They are
 * two different tables, two different scopes and two different access systems, and
 * `Artisan.designWorkshopId` in `schema.prisma` carries the full argument for why one column could
 * not carry both. A record may be filed under either, both, or neither.
 *
 * The visible consequence for a designer is small and worth stating on screen rather than in this
 * comment: **filing a record under a design workshop does not restrict who may read it.**
 * `records.viewable_where` returns `{}` and that is unchanged. This box is a filing label.
 *
 * ── WHAT IT DOES NOT DO, DELIBERATELY ───────────────────────────────────────────────────────────
 *
 * NO SUBMISSION CHECK. `WorkshopSelect` runs `GET /workshops/{id}/submission-check` because a
 * `Workshop` has assignments and a window, and both are things a researcher must learn before saving
 * rather than after. A design workshop has neither: there is no late-submission rule and no
 * assignment roster, only "may you open it", which the server answers on the save itself. Adding a
 * pre-flight here would be a request that could only ever say yes.
 *
 * NO `name`/FormData MIRROR. The value lives in React state and the caller reads it at submit time,
 * exactly as `WorkshopSelect`'s does, so there is one source of truth for it.
 *
 * ── THE FOUR THINGS THAT CHANGED WHEN THIS BECAME THE REFERENCE MOUNT (DROPDOWN_DESIGN §2.12 #1) ─
 *
 * This is the picker eleven others are being made to agree with, so what it does is now a ruling
 * rather than a local choice. Every one of the four is a behaviour change and each closes a defect
 * that shipped:
 *
 * 1. **A FAILED LIST IS NO LONGER AN EMPTY LIST.** It was `listDesignWorkshops(…).catch(() => null)`
 *    followed by `setRows(page?.items ?? [])`, which turned a timeout into an empty array and then
 *    drew *"You are on no design workshop yet. An administrator can add you to one."* — a confident
 *    claim about a grant table, made by a request that never arrived, telling a designer to go and
 *    ask an administrator for access they already hold. The read now answers into a three-state
 *    {@link WorkshopListState} and `lib/workshopOptions` owns which of the four sentences of §3.5 is
 *    true (could-not-be-listed, empty-because-offline, genuinely-empty-scoped, still-asking).
 *
 * 2. **THE BOX SEARCHES THE REPOSITORY.** It used to pass `searchable={false}` with a `capHint`
 *    saying "Open Design workshops to search the whole list" — i.e. leave the record you are in the
 *    middle of filling in. That was the right call while the only box available filtered the eighty
 *    rows already fetched, because such a box answers "No matches" about a workshop that exists. The
 *    primitive now carries `serverQuery`, so the term goes to `GET /design-workshops?search=`, which
 *    matches `title, craftName, clusterName, workshopCode` across the whole table.
 *
 * 3. **THE RECORD'S OWN WORKSHOP IS RECOVERED.** One page is at most eighty rows ordered
 *    `createdAt desc`; a product filed last season points at a workshop nowhere near them, and the
 *    picker used to render a blank box over a filed record — inviting somebody to "fix" it by
 *    picking a different one. `useRecordOffPage` fetches that one id through the open single read
 *    and the builder draws it under "Already on this record".
 *
 * 4. **THE WEB CAN FINALLY UN-FILE.** `noneLabel` draws the `value: ""` row. The server has accepted
 *    the clearance the whole time — `designWorkshopId` is in `services/records.py`'s `CLEARABLE_KEYS`
 *    — and the row to send it was simply never drawn, so a designer who filed a record under the
 *    wrong workshop had no way back from this control at all.
 *
 * ── THE DEFAULT, AND WHY IT IS THE SERVER'S ANSWER ─────────────────────────────────────────────
 *
 * The owner's instruction: *"Whenever a designer goes to create/record any particular record type,
 * the most recently allocated Design and Prototype Workshop should be populated by default."*
 * `lib/designWorkshopDefault.ts` reads that from one endpoint; see its header for why fourteen
 * call sites are not allowed to each decide what "most recently allocated" means.
 *
 * IT IS APPLIED AS AN ID AND NEVER AS A POSITION. `default-for-me` answers by grant or authorship
 * `createdAt`, the list arrives `createdAt desc`, and `designWorkshopOptions` re-sorts by the day
 * the workshop RAN — so the default's row is very often not the first row, and "pick the top one"
 * would pick the wrong workshop. An id that is off the page is recovered by (3) above rather than
 * dropped, which is the whole reason the two changes belong in one release.
 *
 * IT PREFILLS ONLY A CREATE, AND ONLY AN UNTOUCHED ONE. On an edit the stored value wins outright:
 * a form opened on a record filed last month must not silently re-file it under this month's
 * workshop when somebody fixes a typo in the notes. `initial` being `undefined` is what marks a
 * create — the same convention `LocationFields` uses to decide whether it may auto-capture, and for
 * the same reason.
 *
 * AND IT NEVER RAISES THE DIRTY FLAG. A prefill is the app filling a box in, not the designer
 * typing in it, and `useLeaveGuard`'s rule is that "a blank new form announcing unsaved work before
 * anybody types trains researchers to click through the guard". `onChange` fires for a PICK and not
 * for the prefill; the caller wires `markDirty` to it.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { useRecordOffPage } from "@/components/forms/recordPickers";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import { listDesignWorkshops, type DwSummary } from "@/lib/designWorkshops";
import {
  designWorkshopDefaultNote,
  readDesignWorkshopDefault
} from "@/lib/designWorkshopDefault";
import {
  designWorkshopOptions,
  deviceLooksOffline,
  NO_DESIGN_WORKSHOP,
  WORKSHOP_OPTION_PAGE_SIZE,
  workshopCutSentence,
  workshopEmptyLabel,
  workshopListNotice,
  workshopListStandsDown,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";

/**
 * How long after the last keystroke the workshop search goes out.
 *
 * 300 ms, this app's number, and the same one `/design-review`, `DesignWorkshopInspectorsPanel` and
 * `StageReferenceField` use, for their measured reason: the server matches with an `ILIKE '%term%'`
 * that no index can answer, so every keystroke that escapes the debounce is a scan. Clearing the box
 * does NOT wait — an empty term is the unnarrowed list, the one request that is always about to be
 * wanted and never about to be superseded by the next letter.
 */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * "No rows yet", as ONE stable array.
 *
 * `useRecordOffPage` has `rows` in its dependency list, so a fresh `[]` per render would re-run its
 * effect on every keystroke of an unrelated field. It is guarded by a ref and would not re-fire the
 * request, but the churn is free to avoid and the reason is worth stating once: everything handed to
 * that hook has to keep its identity across renders that did not change the answer.
 */
const NO_ROWS: readonly DwSummary[] = [];

export type DesignWorkshopSelectState = {
  /** The chosen workshop, or "" for none. Read this at submit time. */
  workshopId: string;
  /** A PERSON picked. Marks {@link DesignWorkshopSelectState.touched}. */
  setWorkshopId: (id: string) => void;
  /**
   * THE APP filled it in. Does NOT mark touched.
   *
   * Two setters rather than one with a boolean argument, because the distinction is the whole of the
   * dirty-tracking rule and a boolean at a call site is a coin flip. `useLeaveGuard`'s own note is
   * the reason: "a blank new form announcing unsaved work before anybody types trains researchers to
   * click through the guard", which must still mean something an hour later when there IS an
   * interview in the form. `ProcessForm` neutralises its carried-forward values for the same reason.
   */
  prefillWorkshopId: (id: string) => void;
  /** True once the DESIGNER has picked — the caller's cue to mark the form dirty. */
  touched: boolean;
};

/**
 * The state half, so a form can hold the value without rendering the control in the same place.
 *
 * Split for the reason `useWorkshopSelection` is: the value is read inside `submit()`, which is not
 * where the box is drawn, and threading a ref through JSX to get at it is how the two come to
 * disagree.
 */
export function useDesignWorkshopSelection(initial?: string | null): DesignWorkshopSelectState {
  const [workshopId, setId] = useState<string>(initial ?? "");
  const [touched, setTouched] = useState(false);
  const setWorkshopId = useCallback((id: string) => {
    setId(id);
    setTouched(true);
  }, []);
  // No `setTouched` at all, which is the entire difference. See the type's own note.
  const prefillWorkshopId = useCallback((id: string) => setId(id), []);
  return { workshopId, setWorkshopId, prefillWorkshopId, touched };
}

export function DesignWorkshopSelect({
  state,
  /**
   * The record's STORED workshop on an edit, and `undefined` on a create.
   *
   * `undefined` and `null` mean different things and the difference is the whole prefill rule —
   * `undefined` is "this is a new record, fill it in for me", `null` is "this record is stored with
   * no workshop, leave it alone". Same convention, same reason, as `LocationFields.initial`.
   */
  initial,
  saving,
  /**
   * Arm the form's unsaved-changes guard. Called ONLY when a person picks, never for the prefill.
   *
   * A prop rather than the caller watching `state.touched` in an effect, because the two are not the
   * same event: `touched` is a value that stays true, and `markDirty` wants the moment. An effect on
   * the value would also fire on the render after the prefill in any caller that got the ordering
   * slightly wrong, which is the "blank new form announcing unsaved work" defect this whole split
   * exists to avoid. Optional, because `ProcessForm` derives its dirty flag differently from its
   * three siblings and `WorkshopSelect` beside it is likewise optional there.
   */
  onDirty,
  label = "Design & prototype workshop"
}: {
  state: DesignWorkshopSelectState;
  initial?: string | null;
  saving?: boolean;
  onDirty?: () => void;
  label?: string;
}) {
  /** What the read answered. Three states, and the middle one is the whole of change (1) above. */
  const [list, setList] = useState<WorkshopListState<DwSummary>>({ kind: "loading" });
  /**
   * Was the device reachable when the read FAILED?
   *
   * Captured in the `catch` rather than read at render time, because it is a fact about the moment
   * the request died: re-reading `navigator.onLine` on some later render — a keystroke in a field
   * three rows down — would swap "connect and it will load" for "could not be loaded" underneath a
   * reader who has not moved. It picks between two of §3.5's four sentences and nothing else.
   */
  const [online, setOnline] = useState(true);
  /** The term in the panel's box, which is the SERVER'S search and not a filter over what is drawn. */
  const [term, setTerm] = useState("");
  /** A search is in flight. Drives the panel's "Searching…" so an empty list mid-flight reads as
   *  "wait" and never as "there are none" — R3, in the one state the control genuinely cannot know. */
  const [pending, setPending] = useState(true);
  /** Why the box filled itself in, or null. Cleared the moment the designer picks. */
  const [note, setNote] = useState<string | null>(null);
  /**
   * Whether the prefill has already run.
   *
   * A REF AND NOT STATE, and it is released in the effect's cleanup rather than left set — the
   * deadlock shape §17 names. `reactStrictMode` runs setup → cleanup → setup on every mount, so a
   * guard claimed on start and never released would make the prefill run exactly zero times in
   * development and look like the feature simply not working.
   */
  const prefilled = useRef(false);
  /**
   * A generation counter rather than an abort: `apiFetch` carries no `AbortSignal`, so two searches
   * can be in flight at once and the house convention is to count them and IGNORE the late answer.
   * Without it a slow response for "bag" lands after the fast one for "bagru" and the picker shows
   * the wrong list under the typed word — which is precisely when somebody picks the first row
   * without reading it.
   */
  const generation = useRef(0);

  const { setWorkshopId, prefillWorkshopId } = state;
  const isCreate = initial === undefined;

  /*
    TWO EFFECTS, STILL ONE WAIT. They used to be a single `Promise.all`, with the note that issuing
    them in series would double the wait on a village connection for a control the designer has not
    looked at yet. That is still true and still honoured: both effects run on the same commit, so the
    two requests leave together. They are separated because only one of them re-runs — the list is
    re-asked on every debounced keystroke, and the default must be read exactly once or the app would
    fight the designer for the box every time they typed.
  */
  useEffect(() => {
    const trimmed = term.trim();
    const mine = ++generation.current;
    setPending(true);
    const timer = window.setTimeout(
      () => {
        listDesignWorkshops({
          page: 1,
          // NEVER 100 INTO A CONTROL THAT DRAWS 80. One number governs the fetch and the render, so
          // two truncation sentences with two different totals cannot both be true at once.
          pageSize: WORKSHOP_OPTION_PAGE_SIZE,
          search: trimmed || undefined
        })
          .then((page) => {
            if (generation.current !== mine) return;
            setList({ kind: "ok", rows: page.items, total: page.total });
            setPending(false);
          })
          .catch(() => {
            if (generation.current !== mine) return;
            // ORDER MATTERS ONLY IN THE SENSE THAT BOTH LAND TOGETHER: React batches these two, so
            // there is no render in which the state says "failed" and the reason says "online" from
            // a previous failure.
            setOnline(!deviceLooksOffline());
            setList({ kind: "failed" });
            setPending(false);
          });
      },
      trimmed ? SEARCH_DEBOUNCE_MS : 0
    );
    return () => window.clearTimeout(timer);
  }, [term]);

  useEffect(() => {
    if (!isCreate) return;
    let cancelled = false;
    void readDesignWorkshopDefault().then((fallback) => {
      // `readDesignWorkshopDefault` answers `null` rather than throwing — a refused or unreachable
      // default is a box that stays empty, never a form that will not open — so there is no catch
      // arm here to swallow anything.
      if (cancelled || prefilled.current || fallback?.workshopId == null) return;
      prefilled.current = true;
      // `prefillWorkshopId` and NOT `setWorkshopId`: the app filling a box in must not mark the form
      // dirty. See the state type's own note for why that is two setters and not one flag.
      prefillWorkshopId(fallback.workshopId);
      setNote(designWorkshopDefaultNote(fallback));
    });
    return () => {
      cancelled = true;
      // Released, so the StrictMode remount can prefill. See the ref's own note.
      prefilled.current = false;
    };
    // `isCreate` is derived from a prop that cannot change without the form remounting on its own
    // `key`, and the setter is a stable `useCallback`.
  }, [isCreate, prefillWorkshopId]);

  /**
   * THE RECORD'S OWN WORKSHOP, FETCHED BY ID WHEN NO ANSWER HOLDS IT.
   *
   * `null` means "not yet, or there is nothing stored" and never "not there" — options arrive over
   * the network, so for the first second of every mount every value is unmatched, and a control that
   * acted on that mismatch would spend that second reporting the record's own workshop as missing.
   * The hook remembers what it has already asked for, so a 403 or a 404 is asked once and not once
   * per render.
   */
  const rows = list.kind === "ok" ? list.rows : NO_ROWS;
  const stored = useRecordOffPage<DwSummary>("/design-workshops", state.workshopId, rows);

  /**
   * `"recover"` AND NOT `"refuse"`, DECIDED HERE BECAUSE ONLY A CALL SITE CAN DECIDE IT.
   *
   * This control describes a read that is ALREADY TRUE: the record is in that workshop, the fact is
   * on its own page, and withholding the row withholds nothing — it converts a read-only fact into a
   * wrong write, because a blank box over a filed record invites somebody to file it somewhere else.
   * The other answer, `"refuse"`, belongs to a control that AUTHORISES a write which is not yet true
   * and cannot be undone; `AdoptLocalDraftDialog` is that control and this is not.
   *
   * MEMOISED, AND THAT IS LOAD-BEARING RATHER THAN AN OPTIMISATION. `SearchableSelect` re-takes its
   * pin snapshot on `options` IDENTITY whenever a `serverQuery` is set — that is how it knows a new
   * answer landed — and a fresh array every render would call `setPins` with a fresh `Set` every
   * render, which is a state change, which renders again. An unmemoised options array on a
   * server-searched control is an infinite loop, not a slow one.
   */
  const set = useMemo(
    () => designWorkshopOptions(list, { group: true, offPage: { mode: "recover", row: stored } }),
    [list, stored]
  );

  /**
   * SCOPED, ALWAYS. `list_design_workshops` applies `visible_to_clause` — creator OR viewer grant —
   * to everybody but an admin, so an empty answer here is a statement about this account's grants
   * and its next move is to ask an administrator. "No design workshops have been recorded yet" is a
   * statement about the repository and would send a designer off to create a duplicate of a workshop
   * that already exists.
   */
  const voice = useMemo<WorkshopListVoice>(
    () => ({ table: "design", scoped: true, online }),
    [online]
  );

  /*
    ── THE THREE GUARDS A SERVER-SEARCHED PICKER NEEDS, AND WHY EACH OF THEM EXISTS ────────────────

    All three come from one fact: with `serverQuery` the options ARE the answer to the term, so every
    sentence built from "the list is empty" is a sentence about the TERM and not about the account.
    They are written out here rather than folded into `lib/workshopOptions` because that module is
    pure and deliberately knows nothing about a box; the four other design-workshop pickers point at
    this comment rather than restating it.

    1. THE NOTICE IS ASKED OF THE UNNARROWED LIST. With a term typed, an empty answer means the term
       matched nothing — and printing "No design workshops are open to this account. An administrator
       can give you access to one." underneath a search box somebody has just typed into is a claim
       about a grant table produced by a filtered read. The panel says the true thing in the server's
       own stronger words (`serverNoMatchSentence`, which promises the whole list was searched). A
       FAILED read still speaks through the term, because that failure is not about the term at all.

    2. THE CONTROL DOES NOT STAND DOWN WHILE A TERM IS TYPED. R2 disables a picker with nothing in
       it so an unanswerable field cannot block a save — but disabling it here would take away the
       box holding the very term that emptied it, leaving the reader with no way back to the list
       they could see a moment ago. It also does not stand down while LOADING: `workshopEmptyLabel`
       answers "Searching…" in the panel, and a disabled trigger cannot be opened to read it.

    3. THE CUT IS STATED ONCE, HERE, AND `serverQuery.truncated` IS NOT PASSED. `/design-workshops`
       reports a real `total`, so `workshopCutSentence` prints the honest "Showing the first 80 of
       196"; the flag would additionally draw the panel's vaguer "there are more and the server did
       not say how many" under the same list. Two sentences about one cut, in two wordings, is how a
       reader learns that neither is worth reading. The flag arm is for a route that cannot count.
  */
  const searching = term.trim() !== "";
  const notice = list.kind === "ok" && searching ? "" : workshopListNotice(list, voice);
  const cut = workshopCutSentence(set, { term, searchable: true });
  const standDown = list.kind !== "loading" && !searching && workshopListStandsDown(set);

  return (
    <FieldBlock
      label={label}
      hint={
        <>
          {/*
            RULE 10: EVERY CAP SAYS SO, WITH THE NUMBER. A designer on ninety workshops must not read
            a list of eighty as "these are my workshops". The sentence is `selectFilter.ts`'s, through
            `workshopCutSentence`, so this line and the panel's own footer cannot describe one cut in
            two wordings — and its last clause now points at a box that genuinely reaches the rest,
            which is the first time in this control's life that it has been true.
          */}
          {cut ? <p className="text-[11px] leading-4 text-ink-500">{cut}</p> : null}
          {/*
            WHICH OF THE FOUR EMPTY STATES THIS IS. `aria-live`, because the trigger is `disabled` in
            three of them and a disabled control cannot be landed on to hear its own description.
          */}
          {notice ? (
            <p className="text-[11px] leading-4 text-ink-500" aria-live="polite">
              {notice}
            </p>
          ) : null}
          {note ? <p className="text-[11px] leading-4 text-ink-500">{note}</p> : null}
          {/*
            SAID ONCE, ON THE CONTROL THAT COULD BE MISREAD AS A PERMISSION. A designer who thinks
            this box narrows who can see the record will use it as one; the server does not, and
            never has.
          */}
          <p className="text-[11px] leading-4 text-ink-500">
            Files this record under a design and prototype workshop so it appears in that workshop&apos;s
            lists. It does not change who can read the record.
          </p>
        </>
      }
    >
      <Dropdown
        value={state.workshopId}
        onChange={(id) => {
          setWorkshopId(id);
          // A themed dropdown is a `<button>` and fires no native input event, so the form's
          // `onInput` cannot see this. Every themed control on these forms arms the guard by hand.
          onDirty?.();
          // The explanation belonged to the value the picker chose for them. Once they have chosen,
          // it is describing a decision that is no longer the app's.
          setNote(null);
        }}
        options={set.options}
        /*
          THE UN-FILE ROW, drawn by the primitive and never by the builder — two layers building it
          would produce two rows sharing the key "" and a control that cannot say which is selected.
          No `placeholder` beside it: with a `noneLabel` the trigger reads this row back whenever the
          value is "", so a placeholder would be a string nothing can ever draw.
        */
        noneLabel={NO_DESIGN_WORKSHOP}
        /*
          "There is nothing here" is not "your query matched nothing", and it is not "the read failed"
          either. All three used to be one sentence claiming the account had no workshops.
        */
        emptyLabel={workshopEmptyLabel(list, voice)}
        // The box is the SERVER'S. See guard 3 above for why `truncated` is deliberately absent.
        serverQuery={{ value: term, onChange: setTerm, pending }}
        disabled={saving || standDown}
      />
    </FieldBlock>
  );
}
