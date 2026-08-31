"use client";

/**
 * "Name of workshop" — the design workshop's OWN title, offered as a list and ACCEPTED as typed.
 *
 * ── THE OBJECTION THIS ANSWERS, WHICH WAS RIGHT ABOUT THE CONTROL IT REFUSED ────────────────────
 *
 * `stageFieldRoles.ts` says, by name, that `workshopSetup.workshopTitle` must never be in
 * `WORKSHOP_TITLE_FIELD_KEYS`: *"it is not a reference to a `Workshop` row at all, and a dropdown
 * there would refuse a workshop that has no `Workshop` record yet, which is most of them on the day
 * they start."* Every clause of that is still true, and it still forbids what it forbade — a CLOSED
 * picker of `Workshop` rows on this box. It does not reach this control, and the difference is not a
 * technicality:
 *
 *   * this list is `DesignWorkshop` TITLES, not `Workshop` rows, so it is not the reference the
 *     objection is about; and
 *   * **nothing here can refuse an answer.** The name typed into the panel's box is committable in
 *     one keystroke whether or not it matches anything, so a workshop nobody has ever filed is
 *     answered exactly as fast as one with ten years of history. A control that cannot refuse cannot
 *     have the failure the objection describes.
 *
 * What is stored is still a plain string — the same string the box stored yesterday, byte for byte.
 * This is not a REF and `workshopTitle` must never become one; the whole of the argument for that is
 * in `stageFieldRoles.ts` and is not repeated here.
 *
 * ── WHY OFFER THE NAMES AT ALL ─────────────────────────────────────────────────────────────────
 *
 * The same reason `StageWorkshopField` exists one field along, made about the field's own title
 * rather than a referenced record's: a stage entry is a FROZEN COPY that nothing re-resolves, so
 * "Bagru Block Print Workshop 2025" and "Bagru block-printing workshop, 2025" are one fortnight to a
 * reader and two different strings to every group-by and every report cover — and the one in the
 * ministry's document is whichever was typed. A workshop that runs every year, or in three clusters
 * at once, is named three ways by three designers unless the names already on record are in front of
 * them while they type.
 *
 * ── THE LIST IS THE SERVER'S, THE BOX IS THE SERVER'S, AND THERE IS NO CACHE ────────────────────
 *
 * Options come from `GET /design-workshops`, which is scoped by grant for everyone but an admin —
 * so what is offered is "workshops I can already open", which is the only list a designer can
 * confirm anything about. The panel's filter box is wired STRAIGHT TO THAT REQUEST rather than
 * filtering the page already fetched, because a client-side box over a server-truncated page answers
 * *"No matches"* about workshops that exist — and on a naming control that answer is worse than
 * usual: the next thing a person does after "no matches" is type the name again slightly
 * differently, which is the exact divergence this control was added to prevent.
 *
 * ── IS "WORKSHOPS WHOSE NAMES I MAY SEE" AN ACCESS LIST? (R6) — NO, AND THE RULING MATTERS ──────
 *
 * `DROPDOWN_DESIGN.md` R6 forbids caching an ACCESS list because *"a stale access list is wrong in
 * the PERMISSIVE direction"* — a revoked grant still reads as a grant, and a picker must not offer
 * what it cannot honour. That rule governs pickers whose VALUE IS A GRANT-BEARING REFERENCE: an id
 * a record is filed under, a destination for a one-way move, a roster row. It does not govern this
 * one, because the value here is a NAME. Picking a row grants nothing, files nothing and points at
 * nothing; the identical string is committable by hand from the same panel, so a stale row could not
 * offer anything a designer could not already type.
 *
 * TWO CONSEQUENCES, AND THE SECOND IS THE ONE WORTH HAVING. `accessList: false` in the voice below,
 * so the offline sentence stops at *"Connect and it will load"* rather than explaining an absence
 * with a grant-table reason that has nothing to do with it (a wrong reason is the same error as a
 * wrong claim, one clause later). And **this control never stands down.** R2 — *a field may only be
 * mandatory where it is answerable* — is satisfied here without disabling anything, because the box
 * IS the answer: `workshopListStandsDown` is deliberately not called, a failed read leaves the
 * control fully usable, and the sentence underneath says what the list is doing rather than what the
 * designer may not do. It still caches nothing, for the plain reason that this client caches nothing.
 */

import { useEffect, useMemo, useState } from "react";

import { Dropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { inputValue, listDesignWorkshops, type DwField, type DwSummary, type DwValue } from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import {
  deviceLooksOffline,
  workshopEmptyLabel,
  workshopListNotice,
  WORKSHOP_OPTION_PAGE_SIZE,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";

/** This app's debounce, the same third of a second `DesignWorkshopHeaderForm`'s link box uses. */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * What the create row says.
 *
 * THE TERM IS QUOTED AND NOT SUMMARISED. A reader has to be able to see the exact string that would
 * be stored — the capitals, the punctuation, the double space they did not mean to type — and a
 * paraphrase is the one shape that cannot show them. "Use" rather than "Create": nothing is created
 * by answering this box, and a button that promises a record would be promising something the server
 * does not do until the create form is submitted.
 *
 * EXPORTED, because there are now TWO boxes in this client that hold a design workshop's own name:
 * this one and `DesignWorkshopHeaderForm`'s. They write the same column — stage 1's value is
 * promoted onto `DesignWorkshop.title` and wins the moment stage 1 is saved — so a designer meets
 * both, and a second wording of one row is a second row as far as a reader is concerned. The Kotlin
 * twin is `dwWorkshopNameCreateLabel`.
 */
export function workshopNameCreateLabel(term: string) {
  return `Use “${term}” as the name`;
}

/**
 * WHAT IS NOT AT STAKE when this list does not arrive — `WorkshopListVoice.reassurance`'s clause.
 *
 * The module's default closes on *"this record can be saved without it"*, which is a claim about
 * SAVING A RECORD against the thing the picker offers and is simply false here: the picker offers a
 * STRING, and the string is typeable whether or not the list ever arrives. Exported for the same
 * reason as the row above — two boxes, one column, one sentence.
 */
export const WORKSHOP_NAME_REASSURANCE =
  "You can still type the name — the list only saves you from typing it differently twice.";

/**
 * The distinct titles among `rows`, newest first, with the count of sittings sharing each one.
 *
 * DEDUPLICATED BECAUSE ONLY THE NAME IS STORED. Two workshops may legitimately share a title, and
 * offering the same string twice would be a control that appears to distinguish two answers it
 * cannot — the same rule, and the same remedy, as `StageWorkshopField.distinctTitles`. The hint says
 * how many share it, so nobody is told a false singular.
 *
 * ORDER IS THE SERVER'S and is never re-sorted here. `GET /design-workshops` answers newest first,
 * which is the workshop a designer naming one today almost always means; sorting alphabetically
 * would bury this season's between two from 2019.
 *
 * A TITLE LONGER THAN THE FIELD STORES IS WITHHELD AND COUNTED, never silently dropped:
 * `coerce_value` refuses an over-length string, so offering one would offer an option that turns the
 * row into a refused answer on save.
 */
function distinctTitles(
  rows: readonly DwSummary[],
  maxLength: number | undefined
): { titles: { title: string; when: string; count: number }[]; withheld: number } {
  const byTitle = new Map<string, { title: string; when: string; count: number }>();
  let withheld = 0;
  for (const row of rows) {
    const title = row.title?.trim();
    if (!title) continue;
    if (maxLength && title.length > maxLength) {
      withheld += 1;
      continue;
    }
    const existing = byTitle.get(title);
    if (existing) {
      existing.count += 1;
      continue;
    }
    byTitle.set(title, { title, when: formatDate(row.startDate ?? row.createdAt ?? null), count: 1 });
  }
  return { titles: [...byTitle.values()], withheld };
}

export function StageWorkshopNameField({
  field,
  value,
  workshopKind,
  onChange,
  labelId,
  describedBy,
  invalid,
  disabled,
  dictation
}: {
  field: DwField;
  value: DwValue | undefined;
  /**
   * The `WORKSHOP_KIND` token answered on this same row, or `""` where the designer has not chosen
   * one yet.
   *
   * **NARROWS THE OFFER, NEVER THE ANSWER.** With a type chosen the list is the workshops of that
   * type, because that is the set whose naming conventions are worth copying — a Skill Upgradation
   * sitting and a Design Intervention are named to different patterns and mixing them is how a
   * designer picks the wrong precedent. With none chosen it is every workshop this account can open.
   * Either way the create row is untouched: narrowing what is OFFERED can never narrow what can be
   * typed, which is the property that keeps the objection above answered in both states.
   */
  workshopKind: string;
  onChange: (next: DwValue) => void;
  labelId: string;
  describedBy?: string;
  invalid?: boolean;
  disabled?: boolean;
  /**
   * The dictation button, rendered by `FieldInput` and passed down.
   *
   * KEPT, unlike `StageWorkshopField`'s, which is withheld on the list half. There the recogniser's
   * words would be typed OVER a title the repository chose, and a dictated near-miss is the whole
   * defect that control exists to remove. Here the name is this workshop's own and a designer
   * saying it aloud is answering the question, not approximating somebody else's answer — and the
   * list is sitting beside them while they do it, which is what makes a near-miss visible instead of
   * permanent.
   */
  dictation?: React.ReactNode;
}) {
  const [list, setList] = useState<WorkshopListState<DwSummary>>({ kind: "loading" });
  /** The panel's own filter box. */
  const [term, setTerm] = useState("");
  /**
   * A read is outstanding — INCLUDING while the debounce timer is still counting.
   *
   * Covering only the request would leave the third of a second between the last keystroke and the
   * fetch drawing the PREVIOUS term's rows with no filter over them (the local pass is bypassed
   * under `serverQuery`): a list that looks like an answer and is not one. Same flag, same reason,
   * as `DesignWorkshopHeaderForm`'s.
   */
  const [pending, setPending] = useState(true);

  const current = inputValue(value);

  useEffect(() => {
    let cancelled = false;
    const trimmed = term.trim();
    // Announced BEFORE the timer, not inside it — see `pending`.
    setPending(true);
    const timer = window.setTimeout(() => {
      listDesignWorkshops({
        page: 1,
        // `RENDER_CAP` under another name. Asking for more rows than the panel will ever draw is how
        // a picker ends up with two disagreeing cap sentences, one above the other.
        pageSize: WORKSHOP_OPTION_PAGE_SIZE,
        search: trimmed || undefined,
        // AND-ed with the search on the server, never OR-ed, so typing cannot reach past the type
        // the designer chose. Omitted entirely when no type is chosen — `buildQuery` drops
        // `undefined`, and an empty string would be a filter that matches nothing.
        workshopKind: workshopKind || undefined
      })
        .then((result) => {
          if (cancelled) return;
          setList({ kind: "ok", rows: result.items ?? [], total: result.total });
        })
        .catch(() => {
          /*
            A FAILED READ IS NOT AN EMPTY ANSWER, and holding it as `[]` is what turns a dropped
            connection into a confident claim that this account is on no workshop. The state carries
            the difference and `workshopListNotice` prints it. Nothing else changes: the control
            stays usable, because typing was always the answer here.
          */
          if (!cancelled) setList({ kind: "failed" });
        })
        .finally(() => {
          if (!cancelled) setPending(false);
        });
      // Skipped when the box is CLEARED: an empty box is the unnarrowed list, the one request that
      // cannot be superseded by the next letter, and making the way back to the full list wait a
      // third of a second teaches somebody that clearing it does nothing.
    }, trimmed ? SEARCH_DEBOUNCE_MS : 0);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
    /*
      ONE TIMER, AND IT IS THIS EFFECT'S, so a late answer to a term the reader has typed past is
      discarded by `cancelled` rather than by a generation counter — `apiFetch` carries no
      `AbortSignal`, and this is the arrangement `DesignWorkshopHeaderForm` and `/design-review`
      already use for the same reason. Keyed on the chosen TYPE as well as the term, because
      changing the type above changes which list this control is showing.
    */
  }, [term, workshopKind]);

  const rows = useMemo<readonly DwSummary[]>(() => (list.kind === "ok" ? list.rows : []), [list]);
  const { titles, withheld } = useMemo(
    () => distinctTitles(rows, field.maxLength),
    [rows, field.maxLength]
  );

  const options = useMemo<DropdownOption[]>(() => {
    const built: DropdownOption[] = [];
    /*
      THE NAME ALREADY ON THIS ROW IS ALWAYS AN OPTION, and it is first.

      With the box wired to the server the options ARE the answer to the term, so typing three
      letters that do not match this workshop's own name would otherwise drop it out of the list —
      and a control that cannot draw its own current value reads as blank. The obvious repair for a
      blank box is to answer it again, which on the field that names a ministry document overwrites a
      true answer with a guess. Same rule and same reason as `useRecordOffPage` and as
      `StageWorkshopField`'s stored-title row; the hint says where it came from so nobody reads it as
      a workshop the server just offered.
    */
    if (current && !titles.some((option) => option.title === current)) {
      built.push({ value: current, label: current, hint: "already on this workshop" });
    }
    for (const option of titles) {
      built.push({
        value: option.title,
        label: option.title,
        // `hint`, not part of the label: the hint is drawn beside the row and IS searched, while the
        // stored value stays the bare title. Folding the date in would store "… · 12 Mar 2025".
        hint:
          option.count > 1
            ? `${option.count} workshops share this name`
            : option.when === "-"
              ? undefined
              : option.when
      });
    }
    return built;
  }, [current, titles]);

  /**
   * The four sentences, in this app's shared words.
   *
   * `scoped: true` — `GET /design-workshops` is grant-scoped for everyone but an admin, so a
   * genuinely empty answer is a statement about this account's access and not about the repository.
   * `accessList: false` — see the header: this list's value is a name and not a grant, so R6's
   * reason is not this control's reason. `reassurance` says what is actually at stake here, which is
   * nothing: the name can be typed whether or not the list ever arrives.
   */
  const voice: WorkshopListVoice = {
    table: "design",
    accessList: false,
    scoped: true,
    reassurance: WORKSHOP_NAME_REASSURANCE,
    online: !deviceLooksOffline()
  };
  /**
   * THE GENUINELY-EMPTY SENTENCE IS SUPPRESSED WHILE A TERM IS APPLIED, and only that one.
   *
   * With the box wired to the server, an `ok` answer with no rows means one of two completely
   * different things: nothing at all is open to this account, or the three letters just typed matched
   * nothing. `workshopListNotice` cannot see the term and answers the first, so a designer hunting
   * for "Sambalpuri" would be told no design workshops are open to them — a false claim about a grant
   * table, produced by a search. The panel already says the true thing in that case (`emptyListSentence`
   * has a server arm naming the term), so the sentence here has nothing to add and everything to get
   * wrong. A FAILED read still speaks with a term applied, because that fact is about the connection
   * and is true whatever was typed.
   */
  const searchedToNothing = list.kind === "ok" && list.rows.length === 0 && term.trim().length > 0;
  const notice = searchedToNothing ? "" : workshopListNotice(list, voice);

  return (
    <div className="grid gap-1">
      <Dropdown
        value={current}
        onChange={onChange}
        options={options}
        placeholder="Type the name, or pick one already on record"
        emptyLabel={workshopEmptyLabel(list, voice)}
        // NEVER STOOD DOWN ON AN EMPTY OR FAILED LIST — see the header. `disabled` here is the
        // caller's own (a locked stage, a save in flight) and nothing else.
        disabled={disabled}
        // `field.label`. `FieldInput`'s `unlabelled` wrapper emits a bare <span className="field-label">
        // rather than a `Field`/`FieldBlock`, so there is no label context for `SearchableSelect` to
        // compose a name from, and without this the trigger would announce the chosen value and never
        // the question.
        ariaLabel={field.label}
        describedBy={describedBy}
        /*
          THE BOX IS THE SERVER'S. `truncated` is deliberately absent: `GET /design-workshops` reports
          a real `total`, so the cut is stated once, underneath, with its number — a flag arm as well
          would print "there are more and the server did not say how many" under a sentence that has
          just said how many.
        */
        serverQuery={{ value: term, onChange: setTerm, pending }}
        /*
          THE HALF THE OWNER ASKED FOR AND THE HALF THE OBJECTION COULD NOT REFUSE. Whatever is in the
          box is committable, so a workshop that exists nowhere yet is answered as fast as one with a
          history. `onChange` is passed rather than assumed by the primitive — see `SelectCreateAction`.
        */
        createAction={{ label: workshopNameCreateLabel, onCreate: onChange }}
      />
      {dictation}
      {/*
        WHAT THE LIST IS, said out loud. A designer cannot tell "the workshops I can open" from
        "every workshop there is" by looking at a dropdown, and a narrowing nobody announced is
        absence reading as non-existence. The type clause is printed only when a type is chosen,
        because a sentence about a narrowing that is not applied is a sentence about nothing.
      */}
      <p className="text-xs leading-5 text-ink-500">
        {workshopKind ? "Names from workshops of this type." : "Names from workshops you can open."} Type a new one if
        it is not here.
      </p>
      {notice ? <p className="text-xs leading-5 text-ink-500">{notice}</p> : null}
      {withheld > 0 ? (
        // Stated rather than dropped. It cannot happen with today's titles, and the sentence is here
        // because the day it does, a designer must not be left hunting for a name that is on screen
        // nowhere and refused by nothing.
        <p className="text-xs leading-5 text-ink-500">
          {withheld} name{withheld === 1 ? "" : "s"} not offered: over {field.maxLength} characters.
        </p>
      ) : null}
    </div>
  );
}
