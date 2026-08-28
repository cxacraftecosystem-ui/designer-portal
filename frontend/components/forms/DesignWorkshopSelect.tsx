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
 * NO CLIENT-SIDE FILTER OVER A TRUNCATED LIST. The options are one page of
 * `GET /design-workshops`, and the frontend contract §11.5 is explicit that `searchable` over a
 * server-truncated list is a filter box that answers "No matches" about records that exist. So the
 * page is asked for at {@link OPTION_CEILING}, the cap is STATED when it bites, and the sentence
 * points at the destination that can search the whole table. A designer is on a handful of
 * workshops; this ceiling exists for the admin who is on two hundred.
 *
 * ── THE DEFAULT, AND WHY IT IS THE SERVER'S ANSWER ─────────────────────────────────────────────
 *
 * The owner's instruction: *"Whenever a designer goes to create/record any particular record type,
 * the most recently allocated Design and Prototype Workshop should be populated by default."*
 * `lib/designWorkshopDefault.ts` reads that from one endpoint; see its header for why fourteen
 * call sites are not allowed to each decide what "most recently allocated" means.
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

import { useCallback, useEffect, useRef, useState } from "react";

import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { listDesignWorkshops, type DwSummary } from "@/lib/designWorkshops";
import {
  designWorkshopDefaultNote,
  readDesignWorkshopDefault
} from "@/lib/designWorkshopDefault";

/**
 * How many workshops the picker asks for.
 *
 * `RENDER_CAP` in `ui/selectFilter.ts` is 80 and the panel draws no more than that, so asking for
 * the server's ceiling would print two truncation sentences with two different totals — the exact
 * defect §11.5 records ("100 rows into a control that draws 80"). Ask for what can be drawn.
 */
const OPTION_CEILING = 80;

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
  const [rows, setRows] = useState<DwSummary[] | null>(null);
  const [total, setTotal] = useState(0);
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

  const { setWorkshopId, prefillWorkshopId } = state;
  const isCreate = initial === undefined;

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      // BOTH REQUESTS TOGETHER. The list is what the picker draws; the default is what it opens on,
      // and neither needs the other's answer. Issued in series they would double the wait on a
      // village connection for a control the designer has not looked at yet.
      const [page, fallback] = await Promise.all([
        listDesignWorkshops({ page: 1, pageSize: OPTION_CEILING }).catch(() => null),
        isCreate ? readDesignWorkshopDefault() : Promise.resolve(null)
      ]);
      if (cancelled) return;

      // A FAILED LIST IS AN EMPTY LIST WITH A SENTENCE, never a form that will not open. The picker
      // says "could not be listed" through `emptyLabel` below rather than through a banner, because
      // a record form must still be fillable and savable with the workshop left blank.
      setRows(page?.items ?? []);
      setTotal(page?.total ?? 0);

      if (!isCreate || prefilled.current || fallback?.workshopId == null) return;
      prefilled.current = true;
      // `prefillWorkshopId` and NOT `setWorkshopId`: the app filling a box in must not mark the form
      // dirty. See the state type's own note for why that is two setters and not one flag.
      prefillWorkshopId(fallback.workshopId);
      setNote(designWorkshopDefaultNote(fallback));
    })();
    return () => {
      cancelled = true;
      // Released, so the StrictMode remount can prefill. See the ref's own note.
      prefilled.current = false;
    };
    // `isCreate` is derived from a prop that cannot change without the form remounting on its own
    // `key`, and both setters are stable `useCallback`s. The default is deliberately NOT re-read
    // when the designer picks — that would fight them for the box.
  }, [isCreate, prefillWorkshopId]);

  const options: DropdownOption[] = (rows ?? []).map((row) => ({
    value: row.id,
    label: row.title || "Untitled workshop",
    // The three facts that tell two workshops apart at a glance, assembled from what is present so
    // a workshop whose stage 1 is unfinished does not print empty gaps. `hint` is SEARCHED as well
    // as shown (§11.5), which is what makes typing a craft or a place find the row.
    hint:
      [row.craftName, row.clusterName ?? row.state, row.startDate?.slice(0, 10)]
        .filter((part) => !!part && String(part).trim() !== "")
        .join(" · ") || undefined
  }));

  const truncated = Math.max(0, total - options.length);

  return (
    <FieldBlock
      label={label}
      hint={
        <>
          {/*
            RULE 10: EVERY CAP SAYS SO. A designer on ninety workshops must not read a list of eighty
            as "these are my workshops" — and the sentence names the destination that can search the
            whole table, because this panel deliberately has no filter box over a truncated list.
          */}
          {truncated > 0 ? (
            <p className="text-[11px] leading-4 text-ink-500">
              Showing the {options.length} most recent of {total}. Open Design workshops to search the
              whole list, then come back.
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
        options={options}
        placeholder="Not filed under a design workshop"
        // "There is nothing here" is not "your query matched nothing", and on this control the
        // empty state has two causes a designer can act on differently.
        emptyLabel={
          rows === null
            ? "Loading your design workshops…"
            : "You are on no design workshop yet. An administrator can add you to one."
        }
        // Off, and stated: the options are one server-truncated page, so a client-side filter box
        // would answer "No matches" about workshops that exist. §11.5, and `capHint` is what keeps
        // the render cap's own footer honest without one.
        searchable={false}
        capHint="Open Design workshops to search the whole list."
        disabled={saving}
      />
    </FieldBlock>
  );
}
